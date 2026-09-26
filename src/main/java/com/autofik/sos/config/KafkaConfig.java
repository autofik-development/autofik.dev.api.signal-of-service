package com.autofik.sos.config;

import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

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

  private final KafkaProperties kafkaProperties;

  public KafkaConfig(KafkaProperties kafkaProperties) {
    this.kafkaProperties = kafkaProperties;
  }

  // Spring Boot's auto-configured ObjectMapper (used by REST controllers) already has
  // JavaTimeModule registered. JsonSerializer/JsonDeserializer build their own internal
  // ObjectMapper when configured by class reference, so LocalDateTime fields on event
  // records (e.g. SosCreatedEvent.createdAt) fail to (de)serialize unless we register
  // the module here too.
  private static ObjectMapper kafkaObjectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
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

  @Bean
  public ConsumerFactory<String, Object> consumerFactory() {
    Map<String, Object> props = kafkaProperties.buildConsumerProperties();

    // spring.kafka.consumer.properties.spring.json.* (trusted packages, default type) in
    // application.yaml is applied to this instance via JsonDeserializer#configure, which Kafka
    // invokes with the full props map even for pre-built deserializer instances.
    JsonDeserializer<Object> valueDeserializer = new JsonDeserializer<>(kafkaObjectMapper());
    return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory());
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
        new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer));
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
        new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer));
    return factory;
  }
}
