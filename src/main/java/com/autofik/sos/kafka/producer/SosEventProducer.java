package com.autofik.sos.kafka.producer;

import com.autofik.sos.config.KafkaConfig;
import com.autofik.sos.dto.event.BidAcceptedEvent;
import com.autofik.sos.dto.event.BidCancelledEvent;
import com.autofik.sos.dto.event.BidCreatedEvent;
import com.autofik.sos.dto.event.BidRejectedEvent;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.event.OneSignalPushFailedEvent;
import com.autofik.sos.dto.event.SosCreatedEvent;
import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.event.SosStatusUpdatedEvent;
import com.autofik.sos.util.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around KafkaTemplate that centralises topic routing for all SOS domain events. The
 * sosId is always used as the Kafka message key so that all events for one SOS request are
 * guaranteed to land on the same partition (and therefore arrive in order).
 *
 * <p>Every send waits for the surrounding transaction to commit (see {@link AfterCommit}), so no
 * event is published for a change that rolled back.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SosEventProducer {

  private final KafkaTemplate<String, Object> kafkaTemplate;

  // Business counters, one per event kind, counted when the event is handed to Kafka (after the
  // transaction committed). Tags are fixed vocabularies (event kind, status, notification type,
  // topic): never an id, a name or a location. Prometheus sees them only where
  // management.endpoints.web.exposure.include has prometheus.
  private final MeterRegistry meters;

  private void count(String name, String tagKey, String tagValue) {
    meters.counter(name, tagKey, tagValue).increment();
  }

  /** Sends and counts; a send that fails is counted by topic and logged, never thrown. */
  private void send(String topic, String key, Object event, String kind) {
    count("sos.events.published", "kind", kind);
    var result = kafkaTemplate.send(topic, key, event);
    if (result != null) {
      result.whenComplete(
          (ok, error) -> {
            if (error != null) {
              count("sos.events.failed", "topic", topic);
              log.warn("Kafka send to {} failed: {}", topic, error.toString());
            }
          });
    }
  }

  /**
   * Publishes an event indicating a new SOS request has been created.
   *
   * @param event the event payload containing details of the created SOS
   */
  public void publishSosCreated(SosCreatedEvent event) {
    AfterCommit.run(
        () -> send(KafkaConfig.TOPIC_SOS_CREATED, event.sosId().toString(), event, "sos_created"));
  }

  /**
   * Publishes an event indicating a mechanic has submitted a bid for an SOS request.
   *
   * @param event the event payload containing bid creation details
   */
  public void publishBidCreated(BidCreatedEvent event) {
    AfterCommit.run(
        () ->
            send(
                KafkaConfig.TOPIC_SOS_BID_CREATED, event.sosId().toString(), event, "bid_created"));
  }

  /**
   * Publishes an event indicating a bid has been accepted by the user.
   *
   * @param event the event payload containing the accepted bid details
   */
  public void publishBidAccepted(BidAcceptedEvent event) {
    AfterCommit.run(
        () ->
            send(
                KafkaConfig.TOPIC_SOS_BID_ACCEPTED,
                event.sosId().toString(),
                event,
                "bid_accepted"));
  }

  /**
   * Publishes an event indicating a bid has been rejected.
   *
   * @param event the event payload containing the rejected bid details
   */
  public void publishBidRejected(BidRejectedEvent event) {
    AfterCommit.run(
        () ->
            send(
                KafkaConfig.TOPIC_SOS_BID_REJECTED,
                event.sosId().toString(),
                event,
                "bid_rejected"));
  }

  /**
   * Publishes an event indicating a bid has been cancelled by the mechanic.
   *
   * @param event the event payload containing the cancelled bid details
   */
  public void publishBidCancelled(BidCancelledEvent event) {
    AfterCommit.run(
        () ->
            send(
                KafkaConfig.TOPIC_SOS_BID_CANCELLED,
                event.sosId().toString(),
                event,
                "bid_cancelled"));
  }

  /**
   * Publishes an event indicating the lifecycle status of an SOS request has changed.
   *
   * @param event the event payload containing the updated status details
   */
  public void publishStatusUpdated(SosStatusUpdatedEvent event) {
    AfterCommit.run(
        () -> {
          count("sos.status.transitions", "to", String.valueOf(event.toStatus()));
          send(
              KafkaConfig.TOPIC_SOS_STATUS_UPDATED,
              event.sosId().toString(),
              event,
              "status_updated");
        });
  }

  /**
   * Publishes real-time location updates of the assigned mechanic for an SOS request.
   *
   * @param event the event payload containing the mechanic's current coordinates
   */
  public void publishMechanicLocation(MechanicLocationEvent event) {
    AfterCommit.run(
        () ->
            send(
                KafkaConfig.TOPIC_MECHANIC_LOCATION_UPDATED,
                event.sosId().toString(),
                event,
                "mechanic_location"));
  }

  /**
   * Publishes general SOS system notifications destined for users or mechanics.
   *
   * @param event the event payload containing notification message details
   */
  public void publishMediaUploaded(SosMediaUploadedEvent event) {
    AfterCommit.run(
        () ->
            send(
                KafkaConfig.TOPIC_SOS_MEDIA_UPLOADED,
                event.sosId().toString(),
                event,
                "media_uploaded"));
  }

  public void publishNotification(SosNotificationEvent event) {
    // recipientId is null for fan-out notifications (e.g. SOS_CREATED, resolved externally
    // by the notification-service) — null key just means round-robin partitioning for those.
    String key = event.recipientId() != null ? event.recipientId().toString() : null;
    AfterCommit.run(
        () -> {
          count("sos.notifications", "type", String.valueOf(event.type()));
          send(KafkaConfig.TOPIC_SOS_NOTIFICATION, key, event, "notification");
        });
  }

  public void publishOneSignalPushFailed(OneSignalPushFailedEvent event) {
    AfterCommit.run(
        () ->
            send(KafkaConfig.TOPIC_SOS_DLQ, event.recipientId().toString(), event, "push_failed"));
  }
}
