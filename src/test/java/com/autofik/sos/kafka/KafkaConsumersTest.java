package com.autofik.sos.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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

  @Nested
  @ExtendWith(MockitoExtension.class)
  class Notification {

    @Mock OneSignalService oneSignal;
    NotificationConsumer consumer;

    @BeforeEach
    void inlinePushes() {
      // Pushes run on the caller's thread here, so each test can verify straight after consume().
      consumer = new NotificationConsumer(oneSignal, Runnable::run);
    }

    @Test
    void targeted_isPushedToTheRecipient_withTypeAndSosIdAsData() {
      consumer.consume(
          new SosNotificationEvent("BID_ACCEPTED", RECIPIENT, "USER", SOS, "Title", "Body"));

      verify(oneSignal)
          .send(
              RECIPIENT, "Title", "Body", Map.of("type", "BID_ACCEPTED", "sosId", SOS.toString()));
    }

    @Test
    void multiRecipientEvent_isOnePushToEveryRecipient() {
      UUID a = UUID.randomUUID();
      UUID b = UUID.randomUUID();
      consumer.consume(
          new SosNotificationEvent(
              "BID_REJECTED", null, "MECHANIC", SOS, "t", "b", null, null, List.of(a, b)));

      verify(oneSignal)
          .sendToMany(
              List.of(a, b), "t", "b", Map.of("type", "BID_REJECTED", "sosId", SOS.toString()));
      verify(oneSignal, never()).send(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void fanOut_withoutRecipient_isNotPushed() {
      consumer.consume(new SosNotificationEvent("SOS_CREATED", null, "MECHANIC", SOS, "t", "b"));
      verifyNoInteractions(oneSignal);
    }

    @Test
    void nullRecord_isIgnored() {
      assertThatCode(() -> consumer.consume(null)).doesNotThrowAnyException();
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
          .send(any(), anyString(), anyString(), anyMap());
      NotificationConsumer pooled = new NotificationConsumer(oneSignal, 8, 100);
      try {
        long start = System.nanoTime();
        for (int i = 0; i < 16; i++) {
          pooled.consume(
              new SosNotificationEvent("BID_RECEIVED", RECIPIENT, "USER", SOS, "t", "b"));
        }
        long listenerMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(listenerMs).as("listener time for 16 pushes of 200 ms each").isLessThan(500);
        verify(oneSignal, timeout(2_000).times(16)).send(any(), anyString(), anyString(), anyMap());
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
          .send(any(), anyString(), anyString(), anyMap());
      NotificationConsumer tiny = new NotificationConsumer(oneSignal, 1, 1);
      try {
        for (int i = 0; i < 10; i++) {
          assertThatCode(
                  () ->
                      tiny.consume(
                          new SosNotificationEvent(
                              "BID_RECEIVED", RECIPIENT, "USER", SOS, "t", "b")))
              .doesNotThrowAnyException();
        }
        verify(oneSignal, timeout(2_000).times(10)).send(any(), anyString(), anyString(), anyMap());
      } finally {
        tiny.shutdown();
      }
    }

    @Test
    void oneSignalFailure_isSwallowed_soTheOffsetAdvances() {
      doThrow(new IllegalStateException("OneSignal 503"))
          .when(oneSignal)
          .send(any(), anyString(), anyString(), anyMap());

      assertThatCode(
              () ->
                  consumer.consume(
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
