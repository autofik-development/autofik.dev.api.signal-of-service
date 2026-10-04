package com.autofik.sos.config;

import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

  public static final String TOPIC_SOS_CREATED = "sos.created";
  public static final String TOPIC_SOS_BID_CREATED = "sos.bid.created";
  public static final String TOPIC_SOS_BID_ACCEPTED = "sos.bid.accepted";
  public static final String TOPIC_SOS_BID_REJECTED = "sos.bid.rejected";
  public static final String TOPIC_SOS_BID_CANCELLED = "sos.bid.cancelled";
  public static final String TOPIC_SOS_STATUS_UPDATED = "sos.status.updated";
  public static final String TOPIC_SOS_DLQ = "sos.dlq";
  public static final String TOPIC_MECHANIC_LOCATION_UPDATED = "mechanic.location.updated";
  public static final String TOPIC_SOS_NOTIFICATION = "sos.notification";
  public static final String TOPIC_SOS_MEDIA_UPLOADED = "sos.media.uploaded";

  // Partitions per app-managed topic; caps consumer parallelism per topic.
  @Value("${kafka.topic.partitions:3}")
  private int partitionCount;

  // 1 is correct only for this repo's single-broker dev Kafka (see CLAUDE.md) — a multi-broker
  // SIT/prod cluster needs 3 for real durability. Configurable per-environment so this doesn't
  // require a code change (and a redeploy) to fix when SIT/prod Kafka stops being single-broker.
  @Value("${kafka.topic.replication-factor:1}")
  private short replicationFactor;

  // Push notifications make one blocking OneSignal call per record; one consumer thread drained a
  // burst of SOS fan-out at ~3 messages/s. Keep at or below the partition count.
  @Value("${kafka.consumer.notification-concurrency:3}")
  private int notificationConcurrency;

  private final KafkaProperties kafkaProperties;

  public KafkaConfig(KafkaProperties kafkaProperties) {
    this.kafkaProperties = kafkaProperties;
  }

  // Spring Boot's auto-configured ObjectMapper (used by REST controllers) already has
  // JavaTimeModule registered. JsonSerializer/JsonDeserializer build their own internal
  // ObjectMapper when configured by class reference, so LocalDateTime fields on event
  // records (e.g. SosCreatedEvent.createdAt) fail to (de)serialize unless we register
  // the module here too.
  //
  // Dates go out as ISO-8601 strings ("2026-10-04T10:00:05", "2026-10-03T14:05:06.789+07:00"), the
  // same style as the REST and WebSocket payloads. Jackson's default, left on, writes a
  // LocalDateTime as an array [2026,10,4,10,0,5] and an OffsetDateTime as an epoch decimal, which
  // every other service reading these topics would have to special-case. Reading is unchanged: the
  // module still accepts arrays and epoch numbers, so records already on a topic keep working.
  private static ObjectMapper kafkaObjectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Bean
  public ProducerFactory<String, Object> producerFactory() {
    Map<String, Object> props = kafkaProperties.buildProducerProperties();

    JsonSerializer<Object> valueSerializer = new JsonSerializer<>(kafkaObjectMapper());
    return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
  }

  @Bean
  public KafkaTemplate<String, Object> kafkaTemplate() {
    return new KafkaTemplate<>(producerFactory());
  }

  @Bean
  public NewTopic sosCreatedTopic() {
    return TopicBuilder.name(TOPIC_SOS_CREATED)
        .partitions(partitionCount)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic sosBidCreatedTopic() {
    return TopicBuilder.name(TOPIC_SOS_BID_CREATED)
        .partitions(partitionCount)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic sosBidAcceptedTopic() {
    return TopicBuilder.name(TOPIC_SOS_BID_ACCEPTED)
        .partitions(partitionCount)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic sosBidRejectedTopic() {
    return TopicBuilder.name(TOPIC_SOS_BID_REJECTED)
        .partitions(partitionCount)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic sosBidCancelledTopic() {
    return TopicBuilder.name(TOPIC_SOS_BID_CANCELLED)
        .partitions(partitionCount)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic sosStatusUpdatedTopic() {
    return TopicBuilder.name(TOPIC_SOS_STATUS_UPDATED)
        .partitions(partitionCount)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic sosDlqTopic() {
    return TopicBuilder.name(TOPIC_SOS_DLQ).partitions(1).replicas(replicationFactor).build();
  }

  @Bean
  public NewTopic mechanicLocationUpdatedTopic() {
    return TopicBuilder.name(TOPIC_MECHANIC_LOCATION_UPDATED)
        .partitions(partitionCount)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic sosNotificationTopic() {
    return TopicBuilder.name(TOPIC_SOS_NOTIFICATION)
        .partitions(partitionCount)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic sosMediaUploadedTopic() {
    return TopicBuilder.name(TOPIC_SOS_MEDIA_UPLOADED)
        .partitions(partitionCount)
        .replicas(replicationFactor)
        .build();
  }

  // A bare JsonDeserializer throws while the record is being read, before any listener runs, so one
  // malformed message fails the poll over and over and the partition never advances. This wrapper
  // turns the failure into a null value plus a header carrying the exception and the raw bytes,
  // which the error handler below then routes to the dead-letter topic.
  static <T> Deserializer<T> failSafe(JsonDeserializer<T> delegate) {
    return new ErrorHandlingDeserializer<>(delegate);
  }

  // Raw-bytes producer for the dead-letter topic: a record that failed to deserialize has no event
  // object left to serialize, only the bytes that arrived.
  @Bean
  public KafkaTemplate<String, byte[]> deadLetterBytesTemplate() {
    Map<String, Object> props = kafkaProperties.buildProducerProperties();
    return new KafkaTemplate<>(
        new DefaultKafkaProducerFactory<>(
            props, new StringSerializer(), new ByteArraySerializer()));
  }

  @Bean
  public DefaultErrorHandler kafkaErrorHandler() {
    return deadLetterErrorHandler(deadLetterBytesTemplate(), kafkaTemplate());
  }

  // Poison pills go straight to sos.dlq (a record that cannot be deserialized is never retried);
  // a listener that keeps throwing is retried twice, a second apart, then dead-lettered too, so a
  // single bad record can no longer stall the partition.
  static DefaultErrorHandler deadLetterErrorHandler(
      KafkaOperations<String, byte[]> bytesTemplate,
      KafkaOperations<String, Object> objectTemplate) {
    Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
    templates.put(byte[].class, bytesTemplate); // raw payload of a record that failed to parse
    templates.put(Object.class, objectTemplate); // a typed event whose listener kept failing
    // sos.dlq has one partition; the default resolver would reuse the source partition number.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            templates, (record, ex) -> new TopicPartition(TOPIC_SOS_DLQ, 0));
    return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2));
  }

  @Bean
  public ConsumerFactory<String, Object> consumerFactory() {
    Map<String, Object> props = kafkaProperties.buildConsumerProperties();

    // spring.kafka.consumer.properties.spring.json.* (trusted packages, default type) in
    // application.yaml is applied to this instance via JsonDeserializer#configure, which Kafka
    // invokes with the full props map even for pre-built deserializer instances.
    Deserializer<Object> valueDeserializer = failSafe(new JsonDeserializer<>(kafkaObjectMapper()));
    return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory());
    factory.setCommonErrorHandler(kafkaErrorHandler());
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, SosMediaUploadedEvent>
      mediaUploadedListenerContainerFactory() {
    Map<String, Object> props = kafkaProperties.buildConsumerProperties();
    props.remove("spring.json.trusted.packages");
    props.remove("spring.json.value.default.type");
    JsonDeserializer<SosMediaUploadedEvent> deserializer =
        new JsonDeserializer<>(SosMediaUploadedEvent.class, kafkaObjectMapper());
    deserializer.addTrustedPackages("com.autofik.sos.*");
    ConcurrentKafkaListenerContainerFactory<String, SosMediaUploadedEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), failSafe(deserializer)));
    factory.setCommonErrorHandler(kafkaErrorHandler());
    return factory;
  }

  // Separate factory for sos.notification — the global factory defaults to MechanicLocationEvent.
  // Spring Kafka 4.0+ forbids mixing programmatic deserializer config with spring.json.* properties
  // in the same props map, so we strip those keys and configure the deserializer fully via code.
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, SosNotificationEvent>
      notificationListenerContainerFactory() {
    Map<String, Object> props = kafkaProperties.buildConsumerProperties();
    props.remove("spring.json.trusted.packages");
    props.remove("spring.json.value.default.type");
    JsonDeserializer<SosNotificationEvent> deserializer =
        new JsonDeserializer<>(SosNotificationEvent.class, kafkaObjectMapper());
    deserializer.addTrustedPackages("com.autofik.sos.*");
    ConcurrentKafkaListenerContainerFactory<String, SosNotificationEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), failSafe(deserializer)));
    factory.setCommonErrorHandler(kafkaErrorHandler());
    factory.setConcurrency(Math.max(1, notificationConcurrency));
    return factory;
  }
}
