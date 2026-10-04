package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.kafka.consumer.MechanicLocationConsumer;
import com.autofik.sos.kafka.consumer.NotificationConsumer;
import com.autofik.sos.kafka.consumer.SosMediaUploadedConsumer;
import com.autofik.sos.service.OneSignalService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * A Kafka listener that throws is retried forever on the same record, so one bad message stalls the
 * whole partition. Whatever arrives — a null record (tombstone or failed deserialization), missing
 * fields, a downstream that is down — the listener must return normally.
 */
class KafkaConsumerFuzzTest {

  private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
  private final OneSignalService oneSignalService = mock(OneSignalService.class);

  private final MechanicLocationConsumer locationConsumer =
      new MechanicLocationConsumer(messagingTemplate);
  private final NotificationConsumer notificationConsumer =
      new NotificationConsumer(oneSignalService, Runnable::run);
  private final SosMediaUploadedConsumer mediaConsumer =
      new SosMediaUploadedConsumer(messagingTemplate);

  @BeforeTry
  void resetMocks() {
    reset(messagingTemplate, oneSignalService);
  }

  @Property(seed = Fuzz.SEED)
  void locationConsumer_neverThrows(
      @ForAll("locationEvents") MechanicLocationEvent event, @ForAll boolean brokerDown) {
    if (brokerDown) {
      doThrow(new IllegalStateException("broker down"))
          .when(messagingTemplate)
          .convertAndSend(anyString(), any(Object.class));
    }
    assertThatCode(() -> locationConsumer.consume(event)).doesNotThrowAnyException();
  }

  @Property(seed = Fuzz.SEED)
  void notificationConsumer_neverThrows(
      @ForAll("notificationEvents") SosNotificationEvent event, @ForAll boolean pushDown) {
    if (pushDown) {
      doThrow(new IllegalStateException("onesignal down"))
          .when(oneSignalService)
          .send(any(), any(), any(), any(), any());
    }
    assertThatCode(() -> notificationConsumer.consume(event, "sos.notification", 0, 0L, 0L))
        .doesNotThrowAnyException();
  }

  @Property(seed = Fuzz.SEED)
  void mediaConsumer_neverThrows(
      @ForAll("mediaEvents") SosMediaUploadedEvent event, @ForAll boolean brokerDown) {
    if (brokerDown) {
      doThrow(new IllegalStateException("broker down"))
          .when(messagingTemplate)
          .convertAndSend(anyString(), any(Object.class));
    }
    assertThatCode(() -> mediaConsumer.consume(event)).doesNotThrowAnyException();
  }

  // ── generators: every field may be null, numbers may be extreme ─────────────

  private static final List<String> STRINGS =
      List.of("", " ", "x", "SOS_CREATED", "MECHANIC", "\0", "../..", "a".repeat(5_000));
  private static final double[] COORDS = {0.0, -0.0, 11.55, 1e308, Double.NaN, -999.0};

  /** Null a quarter of the time, so every field is regularly missing. */
  private static <T> T maybe(java.util.Random r, T value) {
    return r.nextInt(4) == 0 ? null : value;
  }

  private static String str(java.util.Random r) {
    return maybe(r, STRINGS.get(r.nextInt(STRINGS.size())));
  }

  private static UUID uuid(java.util.Random r) {
    return maybe(r, new UUID(r.nextLong(), r.nextLong()));
  }

  @Provide
  Arbitrary<MechanicLocationEvent> locationEvents() {
    return Arbitraries.randomValue(
        r ->
            r.nextInt(8) == 0
                ? null // a null record: tombstone, or a payload that failed to deserialize
                : new MechanicLocationEvent(
                    uuid(r),
                    uuid(r),
                    COORDS[r.nextInt(COORDS.length)],
                    COORDS[r.nextInt(COORDS.length)],
                    maybe(r, OffsetDateTime.now()),
                    maybe(r, r.nextLong()),
                    maybe(r, r.nextLong()),
                    str(r),
                    str(r)));
  }

  @Provide
  Arbitrary<SosNotificationEvent> notificationEvents() {
    return Arbitraries.randomValue(
        r ->
            r.nextInt(8) == 0
                ? null
                : new SosNotificationEvent(
                    str(r),
                    uuid(r),
                    str(r),
                    uuid(r),
                    str(r),
                    str(r),
                    maybe(r, List.of("https://x/a.jpg")),
                    null));
  }

  @Provide
  Arbitrary<SosMediaUploadedEvent> mediaEvents() {
    return Arbitraries.randomValue(
        r ->
            r.nextInt(8) == 0
                ? null
                : new SosMediaUploadedEvent(
                    uuid(r), uuid(r), str(r), uuid(r), maybe(r, LocalDateTime.now())));
  }
}
