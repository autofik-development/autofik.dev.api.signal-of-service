package com.autofik.sos.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
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

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    public static final String TOPIC_SOS_CREATED                 = "sos.created";
    public static final String TOPIC_SOS_BID_CREATED            = "sos.bid.created";
    public static final String TOPIC_SOS_BID_ACCEPTED           = "sos.bid.accepted";
    public static final String TOPIC_SOS_BID_REJECTED           = "sos.bid.rejected";
    public static final String TOPIC_SOS_BID_CANCELLED          = "sos.bid.cancelled";
    public static final String TOPIC_SOS_STATUS_UPDATED         = "sos.status.updated";
    public static final String TOPIC_SOS_DLQ                    = "sos.dlq";
    public static final String TOPIC_MECHANIC_LOCATION_UPDATED  = "mechanic.location.updated";
    public static final String TOPIC_SOS_NOTIFICATION           = "sos.notification";

    private static final int   PARTITION_COUNT    = 3;
    private static final short REPLICATION_FACTOR = 1;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

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
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(kafkaObjectMapper());
        valueSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public NewTopic sosCreatedTopic() {
        return TopicBuilder.name(TOPIC_SOS_CREATED).partitions(PARTITION_COUNT).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic sosBidCreatedTopic() {
        return TopicBuilder.name(TOPIC_SOS_BID_CREATED).partitions(PARTITION_COUNT).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic sosBidAcceptedTopic() {
        return TopicBuilder.name(TOPIC_SOS_BID_ACCEPTED).partitions(PARTITION_COUNT).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic sosBidRejectedTopic() {
        return TopicBuilder.name(TOPIC_SOS_BID_REJECTED).partitions(PARTITION_COUNT).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic sosBidCancelledTopic() {
        return TopicBuilder.name(TOPIC_SOS_BID_CANCELLED).partitions(PARTITION_COUNT).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic sosStatusUpdatedTopic() {
        return TopicBuilder.name(TOPIC_SOS_STATUS_UPDATED).partitions(PARTITION_COUNT).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic sosDlqTopic() {
        return TopicBuilder.name(TOPIC_SOS_DLQ).partitions(1).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic mechanicLocationUpdatedTopic() {
        return TopicBuilder.name(TOPIC_MECHANIC_LOCATION_UPDATED).partitions(PARTITION_COUNT).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic sosNotificationTopic() {
        return TopicBuilder.name(TOPIC_SOS_NOTIFICATION).partitions(PARTITION_COUNT).replicas(REPLICATION_FACTOR).build();
    }

    @Value("${spring.kafka.consumer.group-id}")
    private String consumerGroupId;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // No fixed target type here (matches the original class-reference config) — the actual
        // type is inferred per-listener from the @KafkaListener method's parameter (e.g. MechanicLocationEvent).
        JsonDeserializer<Object> valueDeserializer = new JsonDeserializer<>(kafkaObjectMapper());
        valueDeserializer.addTrustedPackages("com.autofik.sos.*");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        return factory;
    }
}
