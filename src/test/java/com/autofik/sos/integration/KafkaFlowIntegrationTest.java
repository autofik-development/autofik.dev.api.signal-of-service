package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;

/**
 * Produce, consume, process, side effect: a record put on a topic by anyone must end up as a STOMP
 * frame or a push, and a record that is not for anyone must not.
 */
class KafkaFlowIntegrationTest extends FlowTestSupport {

  private static final Duration WAIT = Duration.ofSeconds(30);

  private String locationJson(UUID sosId, UUID mechanicId, double lat, double lng) {
    return "{\"sosId\":\""
        + sosId
        + "\",\"mechanicId\":\""
        + mechanicId
        + "\",\"latitude\":"
        + lat
        + ",\"longitude\":"
        + lng
        + ",\"timestamp\":\""
        // UTC on purpose: whatever offset the producer wrote, the client must get +07:00.
        + OffsetDateTime.now(ZoneOffset.UTC)
        + "\"}";
  }

  @Test
  void locationEventOnKafka_isBroadcastToTheOwnersSubscription() throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap location = subscribe(customer, "/topic/sos/" + sosId + "/location");
    Thread.sleep(500);

    produce(
        "mechanic.location.updated",
        sosId.toString(),
        locationJson(sosId, UUID.randomUUID(), 11.5611, 104.9311));

    String frame = location.next(WAIT);
    assertThat(frame).as("frame from the Kafka fallback consumer").isNotNull();
    assertThat(parse(frame).get("latitude").asDouble()).isEqualTo(11.5611);
    assertThat(parse(frame).get("timestamp").asString())
        .as("timestamp a client receives over the Kafka path")
        .endsWith("+07:00");
    customer.disconnect();
  }

  @Test
  void locationEventForAnotherSos_isNotDeliveredToThisSubscriber() throws Exception {
    Driver d = newDriver();
    UUID mine = createSosOk(d);
    UUID someoneElses = UUID.randomUUID();
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap location = subscribe(customer, "/topic/sos/" + mine + "/location");
    Thread.sleep(500);

    produce(
        "mechanic.location.updated",
        someoneElses.toString(),
        locationJson(someoneElses, UUID.randomUUID(), 11.5, 104.9));

    assertThat(location.next(Duration.ofSeconds(3))).isNull();
    customer.disconnect();
  }

  @Test
  void mediaUploadedEvent_isBroadcastOnThePerSosMediaTopic() throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap media = subscribe(customer, "/topic/sos/" + sosId + "/media");
    Thread.sleep(500);
    UUID mediaId = UUID.randomUUID();

    produce(
        "sos.media.uploaded",
        sosId.toString(),
        "{\"sosId\":\""
            + sosId
            + "\",\"mediaId\":\""
            + mediaId
            + "\",\"uploadedByRole\":\"USER\",\"uploadedById\":\""
            + d.user().getUserId()
            + "\",\"uploadedAt\":\"2026-10-03T09:30:15\"}");

    String frame = media.next(WAIT);
    assertThat(frame).isNotNull();
    assertThat(parse(frame).get("mediaId").asString()).isEqualTo(mediaId.toString());
    customer.disconnect();
  }

  @Test
  void targetedNotification_isPushedThroughOneSignal() throws Exception {
    UUID recipient = UUID.randomUUID();
    UUID sosId = UUID.randomUUID();

    produce(
        "sos.notification",
        recipient.toString(),
        "{\"type\":\"BID_ACCEPTED\",\"recipientId\":\""
            + recipient
            + "\",\"recipientRole\":\"MECHANIC\",\"sosId\":\""
            + sosId
            + "\",\"title\":\"Accepted\",\"body\":\"Your bid was accepted\"}");

    verify(oneSignalService, timeout(WAIT.toMillis()))
        .send(
            eq(recipient),
            eq("Accepted"),
            eq("Your bid was accepted"),
            eq(Map.of("type", "BID_ACCEPTED", "sosId", sosId.toString())),
            // The listener reads the record's Kafka headers into the push's idempotency key.
            org.mockito.ArgumentMatchers.notNull());
  }

  @Test
  void fanOutNotification_withoutRecipient_isNotPushedToAnyone() throws Exception {
    UUID sosId = UUID.randomUUID();
    String marker = "no-push-" + UUID.randomUUID();

    produce(
        "sos.notification",
        null,
        "{\"type\":\"SOS_CREATED\",\"recipientId\":null,\"recipientRole\":\"MECHANIC\",\"sosId\":\""
            + sosId
            + "\",\"title\":\""
            + marker
            + "\",\"body\":\"b\"}");
    Thread.sleep(3_000);

    verify(oneSignalService, never())
        .send(
            org.mockito.ArgumentMatchers.any(),
            eq(marker),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void aPoisonRecord_doesNotStopTheNextGoodOne_reachingTheSubscriber() throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap location = subscribe(customer, "/topic/sos/" + sosId + "/location");
    Thread.sleep(500);

    produce("mechanic.location.updated", sosId.toString(), "{{ definitely not json");
    produce(
        "mechanic.location.updated",
        sosId.toString(),
        locationJson(sosId, UUID.randomUUID(), 11.5622, 104.9322));

    String frame = location.next(WAIT);
    assertThat(frame).as("good record behind the poison one").isNotNull();
    assertThat(parse(frame).get("latitude").asDouble()).isEqualTo(11.5622);
    customer.disconnect();
  }
}
