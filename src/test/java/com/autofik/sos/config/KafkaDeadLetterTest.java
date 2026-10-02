package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.dto.event.SosNotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.SerializationUtils;

/**
 * A malformed Kafka record used to fail the poll on every retry, so the partition never advanced.
 * These run the real deserializer wrapper and the real error handler against a mocked producer — no
 * broker needed — to pin that such a record is routed to sos.dlq instead.
 */
class KafkaDeadLetterTest {

  private static final byte[] GARBAGE = "not json {{{".getBytes(StandardCharsets.UTF_8);

  private Deserializer<SosNotificationEvent> deserializer() {
    JsonDeserializer<SosNotificationEvent> json =
        new JsonDeserializer<>(
            SosNotificationEvent.class, new ObjectMapper().registerModule(new JavaTimeModule()));
    json.addTrustedPackages("com.autofik.sos.*");
    return KafkaConfig.failSafe(json);
  }

  @Test
  void malformedPayload_isDeserializedToNullInsteadOfThrowing() {
    RecordHeaders headers = new RecordHeaders();

    SosNotificationEvent value = deserializer().deserialize("sos.notification", headers, GARBAGE);

    assertThat(value).isNull();
    // The failure and the raw bytes travel with the record for the error handler to find.
    assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER))
        .isNotNull();
  }

  @Test
  void wellFormedPayload_stillDeserializesNormally() {
    byte[] ok =
        "{\"type\":\"X\",\"recipientId\":null,\"recipientRole\":\"USER\",\"sosId\":null,\"title\":\"t\",\"body\":\"b\"}"
            .getBytes(StandardCharsets.UTF_8);

    SosNotificationEvent value =
        deserializer().deserialize("sos.notification", new RecordHeaders(), ok);

    assertThat(value).isNotNull();
    assertThat(value.type()).isEqualTo("X");
  }

  @Test
  @SuppressWarnings("unchecked")
  void malformedRecord_isPublishedToTheDeadLetterTopicWithItsRawBytes() {
    KafkaOperations<String, byte[]> bytesTemplate = mock(KafkaOperations.class);
    KafkaOperations<String, Object> objectTemplate = mock(KafkaOperations.class);
    when(bytesTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    DefaultErrorHandler handler = KafkaConfig.deadLetterErrorHandler(bytesTemplate, objectTemplate);

    // What the container hands the handler: a record whose value is null and whose headers carry
    // the deserialization failure — taken from the real deserializer, not hand-built.
    RecordHeaders headers = new RecordHeaders();
    deserializer().deserialize("sos.notification", headers, GARBAGE);
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("sos.notification", 2, 42L, "key", null);
    headers.forEach(h -> record.headers().add(h));

    handler.handleOne(
        new DeserializationException("bad", GARBAGE, false, new RuntimeException("bad")),
        record,
        mock(Consumer.class),
        mock(MessageListenerContainer.class));

    ArgumentCaptor<ProducerRecord<String, byte[]>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(bytesTemplate).send(sent.capture());
    assertThat(sent.getValue().topic()).isEqualTo(KafkaConfig.TOPIC_SOS_DLQ);
    // sos.dlq has a single partition, so the source record's partition (2) must not be reused;
    // either an explicit 0 or "producer's choice" lands on the only partition there is.
    Integer partition = sent.getValue().partition();
    assertThat(partition == null || partition == 0).isTrue();
    assertThat(Arrays.equals(sent.getValue().value(), GARBAGE)).isTrue();
  }
}
