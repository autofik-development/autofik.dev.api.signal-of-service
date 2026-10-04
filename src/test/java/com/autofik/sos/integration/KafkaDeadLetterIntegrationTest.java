package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.autofik.sos.config.KafkaConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

/**
 * Closes the check the unit tests could not make: a malformed record on a real broker reaches
 * sos.dlq, and the consumer carries on with the next good record instead of stalling on it.
 */
class KafkaDeadLetterIntegrationTest extends IntegrationTestBase {

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private KafkaProducer<byte[], byte[]> producer() {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    return new KafkaProducer<>(p);
  }

  private KafkaConsumer<byte[], byte[]> deadLetterConsumer() {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(p);
    consumer.subscribe(List.of(KafkaConfig.TOPIC_SOS_DLQ));
    return consumer;
  }

  /**
   * Polls sos.dlq until every given payload has shown up, or the time runs out. Collects everything
   * it reads, so one payload being read while waiting for another is not lost.
   */
  private List<String> missingFromDeadLetters(
      KafkaConsumer<byte[], byte[]> consumer, byte[]... payloads) {
    java.util.Set<String> wanted = new java.util.HashSet<>();
    for (byte[] p : payloads) wanted.add(new String(p, StandardCharsets.UTF_8));
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
    while (!wanted.isEmpty() && System.currentTimeMillis() < deadline) {
      for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(500))) {
        if (r.value() != null) wanted.remove(new String(r.value(), StandardCharsets.UTF_8));
      }
    }
    return List.copyOf(wanted);
  }

  @Test
  void malformedNotification_goesToTheDeadLetterTopic_andTheConsumerKeepsGoing() throws Exception {
    byte[] junk = bytes("this is {{ not json " + UUID.randomUUID());
    UUID recipient = UUID.randomUUID();
    UUID sosId = UUID.randomUUID();
    byte[] good =
        bytes(
            "{\"type\":\"BID_ACCEPTED\",\"recipientId\":\""
                + recipient
                + "\",\"recipientRole\":\"USER\",\"sosId\":\""
                + sosId
                + "\",\"title\":\"t\",\"body\":\"b\"}");

    try (KafkaProducer<byte[], byte[]> producer = producer();
        KafkaConsumer<byte[], byte[]> dlq = deadLetterConsumer()) {
      // The poison pill first, then a good record behind it on the same topic. If the pill stalled
      // the partition, the good record would never be delivered.
      producer
          .send(new ProducerRecord<>(KafkaConfig.TOPIC_SOS_NOTIFICATION, bytes("k"), junk))
          .get();
      producer
          .send(new ProducerRecord<>(KafkaConfig.TOPIC_SOS_NOTIFICATION, bytes("k"), good))
          .get();

      assertThat(missingFromDeadLetters(dlq, junk))
          .as("the malformed record should be published to sos.dlq with its raw bytes")
          .isEmpty();
      verify(oneSignalService, timeout(30_000)).send(eq(recipient), any(), any(), any(), any());
    }
  }

  @Test
  void malformedLocationAndMediaRecords_alsoGoToTheDeadLetterTopic() throws Exception {
    byte[] junkLocation = bytes("{ broken location " + UUID.randomUUID());
    byte[] junkMedia = bytes("[not an object] " + UUID.randomUUID());

    try (KafkaProducer<byte[], byte[]> producer = producer();
        KafkaConsumer<byte[], byte[]> dlq = deadLetterConsumer()) {
      producer
          .send(
              new ProducerRecord<>(
                  KafkaConfig.TOPIC_MECHANIC_LOCATION_UPDATED, bytes("k"), junkLocation))
          .get();
      producer
          .send(new ProducerRecord<>(KafkaConfig.TOPIC_SOS_MEDIA_UPLOADED, bytes("k"), junkMedia))
          .get();

      assertThat(missingFromDeadLetters(dlq, junkLocation, junkMedia))
          .as("poison pills that never reached sos.dlq (location, then media)")
          .isEmpty();
    }
  }

  /** A null value (tombstone) is ignored by the consumer rather than failing it. */
  @Test
  void tombstoneRecord_doesNotStallTheConsumer() throws Exception {
    UUID recipient = UUID.randomUUID();
    byte[] good =
        bytes(
            "{\"type\":\"BID_ACCEPTED\",\"recipientId\":\""
                + recipient
                + "\",\"recipientRole\":\"USER\",\"sosId\":\""
                + UUID.randomUUID()
                + "\",\"title\":\"t\",\"body\":\"b\"}");

    try (KafkaProducer<byte[], byte[]> producer = producer()) {
      producer
          .send(new ProducerRecord<>(KafkaConfig.TOPIC_SOS_NOTIFICATION, bytes("k"), null))
          .get();
      producer
          .send(new ProducerRecord<>(KafkaConfig.TOPIC_SOS_NOTIFICATION, bytes("k"), good))
          .get();
    }

    verify(oneSignalService, timeout(30_000)).send(eq(recipient), any(), any(), any(), any());
  }
}
