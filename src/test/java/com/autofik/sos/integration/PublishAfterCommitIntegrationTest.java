package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.util.AfterCommit;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kafka events and STOMP frames sent from inside a transaction go out once it commits, and not at
 * all if it rolls back. Uses the real producer, the real broker and a real STOMP session.
 */
class PublishAfterCommitIntegrationTest extends FlowTestSupport {

  private static final String UPDATES = "/user/queue/sos-updates";
  private static final String NOTIFICATION_TOPIC = "sos.notification";

  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private SosEventProducer producer;
  @Autowired private SimpMessagingTemplate messagingTemplate;

  private void publishBoth(Mechanic m, UUID sosId) {
    producer.publishNotification(
        new SosNotificationEvent(
            "AFTER_COMMIT_PROBE", m.getMechanicId(), "MECHANIC", sosId, "probe", "probe"));
    // The services wrap each frame the same way; the producer wraps its own sends.
    AfterCommit.run(
        () ->
            messagingTemplate.convertAndSendToUser(
                m.getMechanicId().toString(),
                "/queue/sos-updates",
                Map.of("type", "AFTER_COMMIT_PROBE", "sosId", sosId)));
  }

  @Test
  void aRolledBackTransaction_publishesNoEventAndNoFrame() throws Exception {
    Mechanic m = newMechanic(11.5564, 104.9282);
    UUID sosId = UUID.randomUUID();
    StompSession session =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap frames = subscribe(session, UPDATES);
    awaitSubscriptions(UPDATES, 1);

    try (KafkaConsumer<String, String> events = tap(NOTIFICATION_TOPIC)) {
      assertThatThrownBy(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        publishBoth(m, sosId);
                        throw new IllegalStateException("forced rollback");
                      }))
          .hasMessage("forced rollback");

      assertThatThrownBy(
              () -> awaitRecord(events, m.getMechanicId().toString(), Duration.ofSeconds(5)))
          .isInstanceOf(AssertionError.class);
      assertThat(frames.next(Duration.ofSeconds(2))).as("frame after rollback").isNull();
    }
    session.disconnect();
  }

  @Test
  void aCommittedTransaction_publishesTheEventAndTheFrameOnce() throws Exception {
    Mechanic m = newMechanic(11.5564, 104.9282);
    UUID sosId = UUID.randomUUID();
    StompSession session =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap frames = subscribe(session, UPDATES);
    awaitSubscriptions(UPDATES, 1);

    try (KafkaConsumer<String, String> events = tap(NOTIFICATION_TOPIC)) {
      transactionTemplate.executeWithoutResult(status -> publishBoth(m, sosId));

      assertThat(awaitRecord(events, m.getMechanicId().toString(), Duration.ofSeconds(30)).value())
          .contains("AFTER_COMMIT_PROBE");
      String frame = frames.next(Duration.ofSeconds(10));
      assertThat(frame).as("frame after commit").isNotNull();
      assertThat(parse(frame).get("type").asString()).isEqualTo("AFTER_COMMIT_PROBE");
      assertThat(frames.next(Duration.ofSeconds(1))).as("second frame").isNull();
    }
    session.disconnect();
  }

  @Test
  void outsideAnyTransaction_itPublishesAtOnce() throws Exception {
    Mechanic m = newMechanic(11.5564, 104.9282);
    UUID sosId = UUID.randomUUID();
    StompSession session =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap frames = subscribe(session, UPDATES);
    awaitSubscriptions(UPDATES, 1);

    try (KafkaConsumer<String, String> events = tap(NOTIFICATION_TOPIC)) {
      publishBoth(m, sosId);

      assertThat(awaitRecord(events, m.getMechanicId().toString(), Duration.ofSeconds(30)))
          .isNotNull();
      assertThat(frames.next(Duration.ofSeconds(10))).isNotNull();
    }
    session.disconnect();
  }
}
