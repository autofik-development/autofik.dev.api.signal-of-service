package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

/**
 * A deserializer-configuration clash only surfaces when the consumer configures its deserializers,
 * which no other test does (the context test is excluded in CI). This does what the consumer does
 * on creation — configure the factory's value deserializer with the factory's own properties — and
 * then reads a valid and a malformed payload through it, so a wrapper or property mistake fails
 * here rather than at startup. It stops short of creating a KafkaConsumer, which needs sockets.
 */
class KafkaConfigWiringTest {

  private KafkaConfig config() {
    KafkaProperties props = new KafkaProperties();
    props.getConsumer().setGroupId("wiring-test");
    // Same JSON settings as application.yaml, which the global factory is configured from.
    props.getConsumer().getProperties().put("spring.json.trusted.packages", "com.autofik.sos.*");
    props
        .getConsumer()
        .getProperties()
        .put("spring.json.value.default.type", "com.autofik.sos.dto.event.MechanicLocationEvent");
    return new KafkaConfig(props);
  }

  @SuppressWarnings("unchecked")
  private <T> Deserializer<T> configuredValueDeserializer(
      ConcurrentKafkaListenerContainerFactory<String, T> factory) {
    DefaultKafkaConsumerFactory<String, T> consumerFactory =
        (DefaultKafkaConsumerFactory<String, T>) factory.getConsumerFactory();
    Deserializer<T> deserializer = consumerFactory.getValueDeserializer();
    deserializer.configure(consumerFactory.getConfigurationProperties(), false);
    return deserializer;
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void globalFactory_readsLocationEvents_andSurvivesGarbage() {
    @SuppressWarnings("unchecked")
    Deserializer<Object> d =
        configuredValueDeserializer(
            (ConcurrentKafkaListenerContainerFactory<String, Object>)
                config().kafkaListenerContainerFactory());
    String json = "{\"sosId\":\"" + UUID.randomUUID() + "\",\"latitude\":11.5,\"longitude\":104.9}";

    Object value = d.deserialize("mechanic.location.updated", new RecordHeaders(), bytes(json));

    assertThat(value).isInstanceOf(MechanicLocationEvent.class);
    assertThat(d.deserialize("mechanic.location.updated", new RecordHeaders(), bytes("{{ nope")))
        .isNull();
  }

  @Test
  void notificationFactory_readsNotifications_andSurvivesGarbage() {
    Deserializer<SosNotificationEvent> d =
        configuredValueDeserializer(config().notificationListenerContainerFactory());
    String json = "{\"type\":\"X\",\"recipientRole\":\"USER\",\"title\":\"t\",\"body\":\"b\"}";

    SosNotificationEvent value =
        d.deserialize("sos.notification", new RecordHeaders(), bytes(json));

    assertThat(value).isNotNull();
    assertThat(value.type()).isEqualTo("X");
    assertThat(d.deserialize("sos.notification", new RecordHeaders(), bytes("{{ nope"))).isNull();
  }

  @Test
  void mediaUploadedFactory_readsEvents_andSurvivesGarbage() {
    Deserializer<SosMediaUploadedEvent> d =
        configuredValueDeserializer(config().mediaUploadedListenerContainerFactory());
    String json = "{\"sosId\":\"" + UUID.randomUUID() + "\",\"uploadedByRole\":\"USER\"}";

    SosMediaUploadedEvent value =
        d.deserialize("sos.media.uploaded", new RecordHeaders(), bytes(json));

    assertThat(value).isNotNull();
    assertThat(value.uploadedByRole()).isEqualTo("USER");
    assertThat(d.deserialize("sos.media.uploaded", new RecordHeaders(), bytes("{{ nope"))).isNull();
  }
}
