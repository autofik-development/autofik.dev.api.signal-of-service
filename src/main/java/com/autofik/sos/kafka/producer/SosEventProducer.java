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

/**
 * Thin wrapper around KafkaTemplate that centralises topic routing for all SOS domain events.
 * The sosId is always used as the Kafka message key so that all events for one SOS request
 * are guaranteed to land on the same partition (and therefore arrive in order).
 */
@Component
@RequiredArgsConstructor
public class SosEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publishes an event indicating a new SOS request has been created.
     *
     * @param event the event payload containing details of the created SOS
     */
    public void publishSosCreated(SosCreatedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_CREATED, event.sosId().toString(), event);
    }

    /**
     * Publishes an event indicating a mechanic has submitted a bid for an SOS request.
     *
     * @param event the event payload containing bid creation details
     */
    public void publishBidCreated(BidCreatedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_BID_CREATED, event.sosId().toString(), event);
    }

    /**
     * Publishes an event indicating a bid has been accepted by the user.
     *
     * @param event the event payload containing the accepted bid details
     */
    public void publishBidAccepted(BidAcceptedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_BID_ACCEPTED, event.sosId().toString(), event);
    }

    /**
     * Publishes an event indicating a bid has been rejected.
     *
     * @param event the event payload containing the rejected bid details
     */
    public void publishBidRejected(BidRejectedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_BID_REJECTED, event.sosId().toString(), event);
    }

    /**
     * Publishes an event indicating a bid has been cancelled by the mechanic.
     *
     * @param event the event payload containing the cancelled bid details
     */
    public void publishBidCancelled(BidCancelledEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_BID_CANCELLED, event.sosId().toString(), event);
    }

    /**
     * Publishes an event indicating the lifecycle status of an SOS request has changed.
     *
     * @param event the event payload containing the updated status details
     */
    public void publishStatusUpdated(SosStatusUpdatedEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_STATUS_UPDATED, event.sosId().toString(), event);
    }

    /**
     * Publishes real-time location updates of the assigned mechanic for an SOS request.
     *
     * @param event the event payload containing the mechanic's current coordinates
     */
    public void publishMechanicLocation(MechanicLocationEvent event) {
        kafkaTemplate.send(KafkaConfig.TOPIC_MECHANIC_LOCATION_UPDATED, event.sosId().toString(), event);
    }

    /**
     * Publishes general SOS system notifications destined for users or mechanics.
     *
     * @param event the event payload containing notification message details
     */
    public void publishNotification(SosNotificationEvent event) {
        // recipientId is null for fan-out notifications (e.g. SOS_CREATED, resolved externally
        // by the notification-service) — null key just means round-robin partitioning for those.
        String key = event.recipientId() != null ? event.recipientId().toString() : null;
        kafkaTemplate.send(KafkaConfig.TOPIC_SOS_NOTIFICATION, key, event);
    }
}