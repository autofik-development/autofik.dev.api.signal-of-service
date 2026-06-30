package com.autofik.sos.kafka.producer;

import com.autofik.sos.config.KafkaConfig;
import com.autofik.sos.dto.event.BidAcceptedEvent;
import com.autofik.sos.dto.event.BidCancelledEvent;
import com.autofik.sos.dto.event.BidCreatedEvent;
import com.autofik.sos.dto.event.BidRejectedEvent;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.event.SosCreatedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.event.SosStatusUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component 
@RequiredArgsConstructor
public class SosEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishSosCreated(SosCreatedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_CREATED, event.sosId().toString(), event);
    }

    public void publishBidCreated(BidCreatedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_BID_CREATED, event.sosId().toString(), event);
    }

    public void publishBidAccepted(BidAcceptedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_BID_ACCEPTED, event.sosId().toString(), event);
    }

    public void publishBidRejected(BidRejectedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_BID_REJECTED, event.sosId().toString(), event);
    }

    public void publishBidCancelled(BidCancelledEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_BID_CANCELLED, event.sosId().toString(), event);
    }

    public void publishStatusUpdated(SosStatusUpdatedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_STATUS_UPDATED, event.sosId().toString(), event);
    }

    public void publishMechanicLocation(MechanicLocationEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_MECHANIC_LOCATION_UPDATED, event.sosId().toString(), event);
    }

    public void publishNotification(SosNotificationEvent event) {
        // recipientId is null for fan-out notifications (e.g. SOS_CREATED, resolved externally
        // by the notification-service) — null key just means round-robin partitioning for those.
        String key = event.recipientId() != null ? event.recipientId().toString() : null;
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_NOTIFICATION, key, event);
    }
}