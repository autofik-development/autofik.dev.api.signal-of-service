package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.external.EtaInfo;
import com.autofik.sos.redis.MechanicLocationRedisListener;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Producer bytes read back by the consumer, both built from the real KafkaConfig, so a serializer
 * and a deserializer that drift apart (a date format, a missing module, a renamed field) fail here.
 * The wiring test only reads hand-written JSON; this reads what the app actually writes.
 */
class PayloadRoundTripTest {

  private static final UUID SOS = UUID.randomUUID();
  private static final UUID MECH = UUID.randomUUID();

  private KafkaConfig config() {
    KafkaProperties props = new KafkaProperties();
    props.getConsumer().setGroupId("round-trip");
    props.getConsumer().getProperties().put("spring.json.trusted.packages", "com.autofik.sos.*");
    props
        .getConsumer()
        .getProperties()
        .put("spring.json.value.default.type", MechanicLocationEvent.class.getName());
    props.getProducer().getProperties().put("spring.json.add.type.headers", "false");
    return new KafkaConfig(props);
  }

  @SuppressWarnings("unchecked")
  private Serializer<Object> serializer(KafkaConfig c) {
    DefaultKafkaProducerFactory<String, Object> pf =
        (DefaultKafkaProducerFactory<String, Object>) c.producerFactory();
    Serializer<Object> s = pf.getValueSerializer();
    s.configure(pf.getConfigurationProperties(), false);
    return s;
  }

  @SuppressWarnings("unchecked")
  private <T> Deserializer<T> deserializer(ConcurrentKafkaListenerContainerFactory<String, T> f) {
    DefaultKafkaConsumerFactory<String, T> cf =
        (DefaultKafkaConsumerFactory<String, T>) f.getConsumerFactory();
    Deserializer<T> d = cf.getValueDeserializer();
    d.configure(cf.getConfigurationProperties(), false);
    return d;
  }

  @Test
  @SuppressWarnings("unchecked")
  void locationEvent_withOffsetTimestampAndEta_survivesTheKafkaFallbackPath() {
    KafkaConfig c = config();
    MechanicLocationEvent sent =
        new MechanicLocationEvent(
            SOS,
            MECH,
            11.556374,
            104.928207,
            OffsetDateTime.of(2026, 10, 3, 14, 5, 6, 789_000_000, ZoneOffset.ofHours(7)),
            1234L,
            321L,
            "1.2 km",
            "5 mins");

    byte[] bytes = serializer(c).serialize("mechanic.location.updated", sent);
    Object read =
        deserializer(
                (ConcurrentKafkaListenerContainerFactory<String, Object>)
                    c.kafkaListenerContainerFactory())
            .deserialize("mechanic.location.updated", new RecordHeaders(), bytes);

    assertThat(read).isInstanceOf(MechanicLocationEvent.class);
    MechanicLocationEvent got = (MechanicLocationEvent) read;
    // OffsetDateTime equality is offset-sensitive; the instant is what must not move.
    assertThat(got.timestamp().toInstant()).isEqualTo(sent.timestamp().toInstant());
    assertThat(got).usingRecursiveComparison().ignoringFields("timestamp").isEqualTo(sent);
  }

  @Test
  void notificationEvent_withPhotosAndEstimate_roundTrips() {
    KafkaConfig c = config();
    SosNotificationEvent sent =
        new SosNotificationEvent(
            "SOS_CREATED",
            MECH,
            "MECHANIC",
            SOS,
            "New SOS",
            "Tow needed",
            List.of("https://example.invalid/a.jpg"),
            new EtaInfo(4200L, 600L, "4.2 km", "10 mins"));

    byte[] bytes = serializer(c).serialize("sos.notification", sent);
    SosNotificationEvent got =
        deserializer(c.notificationListenerContainerFactory())
            .deserialize("sos.notification", new RecordHeaders(), bytes);

    assertThat(got).isEqualTo(sent);
  }

  @Test
  void mediaUploadedEvent_withLocalDateTime_roundTrips() {
    KafkaConfig c = config();
    SosMediaUploadedEvent sent =
        new SosMediaUploadedEvent(
            SOS, UUID.randomUUID(), "USER", MECH, LocalDateTime.of(2026, 10, 3, 9, 30, 15));

    byte[] bytes = serializer(c).serialize("sos.media.uploaded", sent);
    SosMediaUploadedEvent got =
        deserializer(c.mediaUploadedListenerContainerFactory())
            .deserialize("sos.media.uploaded", new RecordHeaders(), bytes);

    assertThat(got).isEqualTo(sent);
  }

  /**
   * Redis pub/sub path: MechanicServiceImpl writes with the app's Jackson 3 mapper, the listener
   * reads with the same. The existing listener test mocks the mapper, so it cannot catch a format
   * the real mapper refuses.
   */
  @Test
  void locationEvent_publishedToRedis_isRelayedUnchanged() throws Exception {
    ObjectMapper mapper = JsonMapper.builder().build();
    SimpMessagingTemplate stomp = mock(SimpMessagingTemplate.class);
    MechanicLocationRedisListener listener = new MechanicLocationRedisListener(stomp, mapper);
    MechanicLocationEvent sent =
        new MechanicLocationEvent(
            SOS,
            MECH,
            11.5,
            104.9,
            OffsetDateTime.of(2026, 10, 3, 14, 0, 0, 0, ZoneOffset.ofHours(7)),
            null,
            null,
            null,
            null);
    byte[] body = mapper.writeValueAsString(sent).getBytes(StandardCharsets.UTF_8);

    listener.onMessage(
        new DefaultMessage(
            (MechanicLocationRedisListener.CHANNEL_PREFIX + SOS).getBytes(StandardCharsets.UTF_8),
            body),
        null);

    ArgumentCaptor<MechanicLocationEvent> relayed =
        ArgumentCaptor.forClass(MechanicLocationEvent.class);
    verify(stomp).convertAndSend(eq("/topic/sos/" + SOS + "/location"), relayed.capture());
    // Jackson 3 reads +07:00 back as UTC (Z); the listener puts Cambodian time back before
    // relaying,
    // so the instant and the +07:00 offset both reach the client.
    assertThat(relayed.getValue().timestamp().toInstant()).isEqualTo(sent.timestamp().toInstant());
    assertThat(relayed.getValue().timestamp().getOffset()).isEqualTo(ZoneOffset.ofHours(7));
    assertThat(relayed.getValue())
        .usingRecursiveComparison()
        .ignoringFields("timestamp")
        .isEqualTo(sent);
  }
}
