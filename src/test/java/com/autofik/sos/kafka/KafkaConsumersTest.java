package com.autofik.sos.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.kafka.consumer.NotificationConsumer;
import com.autofik.sos.kafka.consumer.SosMediaUploadedConsumer;
import com.autofik.sos.service.OneSignalService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * The two consumers that had no direct test. Both must never throw: a throw makes the container
 * retry the same record and then dead-letter it, which for a push or a socket relay is worse than
 * logging and moving on.
 */
class KafkaConsumersTest {

  private static final UUID SOS = UUID.randomUUID();
  private static final UUID RECIPIENT = UUID.randomUUID();
  private static final String TOPIC = "sos.notification";
  private static final long TIMESTAMP = 1_759_550_000_000L;

  @Nested
  @ExtendWith(MockitoExtension.class)
  class Notification {

    @Mock OneSignalService oneSignal;
    NotificationConsumer consumer;
    long nextOffset;

    /** Delivers the event as the next record on partition 0, as the listener container would. */
    void consume(NotificationConsumer target, SosNotificationEvent event) {
      target.consume(event, TOPIC, 0, nextOffset++, TIMESTAMP);
    }

    @BeforeEach
    void inlinePushes() {
      // Pushes run on the caller's thread here, so each test can verify straight after consume().
      consumer = new NotificationConsumer(oneSignal, Runnable::run);
    }

    @Test
    void targeted_isPushedToTheRecipient_withTypeAndSosIdAsData() {
      consume(
          consumer,
          new SosNotificationEvent("BID_ACCEPTED", RECIPIENT, "USER", SOS, "Title", "Body"));

      verify(oneSignal)
          .send(
              eq(RECIPIENT),
              eq("Title"),
              eq("Body"),
              eq(Map.of("type", "BID_ACCEPTED", "sosId", SOS.toString())),
              any(UUID.class));
    }

    @Test
    void multiRecipientEvent_isOnePushToEveryRecipient() {
      UUID a = UUID.randomUUID();
      UUID b = UUID.randomUUID();
      consume(
          consumer,
          new SosNotificationEvent(
              "BID_REJECTED", null, "MECHANIC", SOS, "t", "b", null, null, List.of(a, b)));

      verify(oneSignal)
          .sendToMany(
              eq(List.of(a, b)),
              eq("t"),
              eq("b"),
              eq(Map.of("type", "BID_REJECTED", "sosId", SOS.toString())),
              any(UUID.class));
      verify(oneSignal, never()).send(any(), anyString(), anyString(), anyMap(), any());
    }

    /**
     * Kafka delivers at least once. A redelivered record must carry the same idempotency key, so
     * OneSignal drops the second push instead of showing the notification twice.
     */
    @Test
    void redeliveredRecord_sendsTheSameIdempotencyKey() {
      SosNotificationEvent event =
          new SosNotificationEvent("BID_ACCEPTED", RECIPIENT, "USER", SOS, "t", "b");
      consumer.consume(event, TOPIC, 2, 41L, TIMESTAMP);
      consumer.consume(event, TOPIC, 2, 41L, TIMESTAMP);

      ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
      verify(oneSignal, times(2))
          .send(eq(RECIPIENT), anyString(), anyString(), anyMap(), keys.capture());
      assertThat(keys.getAllValues().get(0)).isNotNull().isEqualTo(keys.getAllValues().get(1));
    }

    /** Two different records with the same content are two real notifications. */
    @Test
    void differentRecords_sendDifferentKeys() {
      SosNotificationEvent event =
          new SosNotificationEvent("BID_RECEIVED", RECIPIENT, "USER", SOS, "t", "b");
      consumer.consume(event, TOPIC, 0, 7L, TIMESTAMP);
      consumer.consume(event, TOPIC, 0, 8L, TIMESTAMP);
      consumer.consume(event, TOPIC, 1, 7L, TIMESTAMP);
      // Same offset after the topic was recreated: the new record has a later timestamp.
      consumer.consume(event, TOPIC, 0, 7L, TIMESTAMP + 60_000);

      ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
      verify(oneSignal, times(4))
          .send(eq(RECIPIENT), anyString(), anyString(), anyMap(), keys.capture());
      assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void fanOut_withoutRecipient_isNotPushed() {
      consume(consumer, new SosNotificationEvent("SOS_CREATED", null, "MECHANIC", SOS, "t", "b"));
      verifyNoInteractions(oneSignal);
    }

    @Test
    void nullRecord_isIgnored() {
      assertThatCode(() -> consume(consumer, null)).doesNotThrowAnyException();
      verifyNoInteractions(oneSignal);
    }

    /**
     * Each OneSignal call is an HTTPS round trip (~570 ms measured from the load-test stack). Sent
     * one at a time on the listener thread, three consumers drained ~5 pushes/s and a burst left a
     * 116k-record backlog. The pool must take the slow call off the listener thread.
     */
    @Test
    void slowPushes_runInParallel_soTheListenerIsNotHeldPerRecord() throws Exception {
      doAnswer(
              inv -> {
                Thread.sleep(200);
                return null;
              })
          .when(oneSignal)
          .send(any(), anyString(), anyString(), anyMap(), any());
      NotificationConsumer pooled = new NotificationConsumer(oneSignal, 8, 100);
      try {
        long start = System.nanoTime();
        for (int i = 0; i < 16; i++) {
          consume(
              pooled, new SosNotificationEvent("BID_RECEIVED", RECIPIENT, "USER", SOS, "t", "b"));
        }
        long listenerMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(listenerMs).as("listener time for 16 pushes of 200 ms each").isLessThan(500);
        verify(oneSignal, timeout(2_000).times(16))
            .send(any(), anyString(), anyString(), anyMap(), any());
      } finally {
        pooled.shutdown();
      }
    }

    /** A full pool slows the listener down (CallerRuns) instead of dropping or piling up pushes. */
    @Test
    void fullPool_listenerSendsTheOverflowItself_andNothingIsDropped() throws Exception {
      doAnswer(
              inv -> {
                Thread.sleep(50);
                return null;
              })
          .when(oneSignal)
          .send(any(), anyString(), anyString(), anyMap(), any());
      NotificationConsumer tiny = new NotificationConsumer(oneSignal, 1, 1);
      try {
        for (int i = 0; i < 10; i++) {
          assertThatCode(
                  () ->
                      consume(
                          tiny,
                          new SosNotificationEvent(
                              "BID_RECEIVED", RECIPIENT, "USER", SOS, "t", "b")))
              .doesNotThrowAnyException();
        }
        verify(oneSignal, timeout(2_000).times(10))
            .send(any(), anyString(), anyString(), anyMap(), any());
      } finally {
        tiny.shutdown();
      }
    }

    @Test
    void oneSignalFailure_isSwallowed_soTheOffsetAdvances() {
      doThrow(new IllegalStateException("OneSignal 503"))
          .when(oneSignal)
          .send(any(), anyString(), anyString(), anyMap(), any());

      assertThatCode(
              () ->
                  consume(
                      consumer,
                      new SosNotificationEvent("BID_ACCEPTED", RECIPIENT, "USER", SOS, "t", "b")))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @ExtendWith(MockitoExtension.class)
  class MediaUploaded {

    @Mock SimpMessagingTemplate messagingTemplate;
    @InjectMocks SosMediaUploadedConsumer consumer;

    private final SosMediaUploadedEvent event =
        new SosMediaUploadedEvent(SOS, UUID.randomUUID(), "USER", RECIPIENT, LocalDateTime.now());

    @Test
    void relaysToThePerSosMediaTopic() {
      consumer.consume(event);
      verify(messagingTemplate).convertAndSend("/topic/sos/" + SOS + "/media", event);
    }

    @Test
    void nullRecord_isIgnored() {
      consumer.consume(null);
      verifyNoInteractions(messagingTemplate);
    }

    @Test
    void brokerFailure_isSwallowed() {
      doThrow(new IllegalStateException("broker down"))
          .when(messagingTemplate)
          .convertAndSend(anyString(), any(Object.class));
      assertThatCode(() -> consumer.consume(event)).doesNotThrowAnyException();
    }
  }
}
