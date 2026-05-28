package com.autofik.sos.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.apache.kafka.clients.admin.NewTopic;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    public static final String TOPIC_SOS_CREATED        = "sos.created";
    public static final String TOPIC_SOS_BID_CREATED    = "sos.bid.created";
    public static final String TOPIC_SOS_BID_ACCEPTED   = "sos.bid.accepted";
    public static final String TOPIC_SOS_BID_REJECTED   = "sos.bid.rejected";
    public static final String TOPIC_SOS_STATUS_UPDATED = "sos.status.updated";
    public static final String TOPIC_SOS_DLQ            = "sos.dlq";

    private static final int   PARTITION_COUNT    = 3;
    private static final short REPLICATION_FACTOR = 1;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(props);
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
    public NewTopic sosStatusUpdatedTopic() {
        return TopicBuilder.name(TOPIC_SOS_STATUS_UPDATED).partitions(PARTITION_COUNT).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic sosDlqTopic() {
        return TopicBuilder.name(TOPIC_SOS_DLQ).partitions(1).replicas(REPLICATION_FACTOR).build();
    }
}
