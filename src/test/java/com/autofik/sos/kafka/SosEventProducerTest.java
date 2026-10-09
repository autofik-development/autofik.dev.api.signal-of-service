package com.autofik.sos.kafka;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

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
import com.autofik.sos.kafka.producer.SosEventProducer;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Topic routing and keys. The key decides the partition, and per-SOS ordering only holds while
 * every event for one SOS carries the same key, so a wrong key here is a silent ordering bug.
 */
@ExtendWith(MockitoExtension.class)
class SosEventProducerTest {

  @Mock KafkaTemplate<String, Object> kafkaTemplate;

  @InjectMocks SosEventProducer producer;

  private final UUID sosId = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();

  @Test
  void sosCreated_goesToSosCreated_keyedBySosId() {
    SosCreatedEvent e =
        new SosCreatedEvent(
            sosId, other, other.toString(), "TOW", "d", 11.5, 104.9, null, LocalDateTime.now());
    producer.publishSosCreated(e);
    verify(kafkaTemplate).send(KafkaConfig.TOPIC_SOS_CREATED, sosId.toString(), e);
  }

  @Test
  void bidEvents_goToTheirOwnTopics_allKeyedBySosId() {
    BidCreatedEvent created =
        new BidCreatedEvent(other, sosId, other, null, null, null, null, null, LocalDateTime.now());
    BidAcceptedEvent accepted =
        new BidAcceptedEvent(other, sosId, other, other, 11.5, 104.9, LocalDateTime.now());
    BidRejectedEvent rejected = new BidRejectedEvent(sosId, List.of(other));
    BidCancelledEvent cancelled = new BidCancelledEvent(other, sosId, other, LocalDateTime.now());

    producer.publishBidCreated(created);
    producer.publishBidAccepted(accepted);
    producer.publishBidRejected(rejected);
    producer.publishBidCancelled(cancelled);

    verify(kafkaTemplate).send(KafkaConfig.TOPIC_SOS_BID_CREATED, sosId.toString(), created);
    verify(kafkaTemplate).send(KafkaConfig.TOPIC_SOS_BID_ACCEPTED, sosId.toString(), accepted);
    verify(kafkaTemplate).send(KafkaConfig.TOPIC_SOS_BID_REJECTED, sosId.toString(), rejected);
    verify(kafkaTemplate).send(KafkaConfig.TOPIC_SOS_BID_CANCELLED, sosId.toString(), cancelled);
  }

  @Test
  void statusLocationAndMedia_areKeyedBySosId() {
    SosStatusUpdatedEvent status =
        new SosStatusUpdatedEvent(sosId, other, other, null, null, LocalDateTime.now());
    MechanicLocationEvent location =
        new MechanicLocationEvent(
            sosId, other, 11.5, 104.9, OffsetDateTime.now(), null, null, null, null);
    SosMediaUploadedEvent media =
        new SosMediaUploadedEvent(sosId, other, "USER", other, LocalDateTime.now());

    producer.publishStatusUpdated(status);
    producer.publishMechanicLocation(location);
    producer.publishMediaUploaded(media);

    verify(kafkaTemplate).send(KafkaConfig.TOPIC_SOS_STATUS_UPDATED, sosId.toString(), status);
    verify(kafkaTemplate)
        .send(KafkaConfig.TOPIC_MECHANIC_LOCATION_UPDATED, sosId.toString(), location);
    verify(kafkaTemplate).send(KafkaConfig.TOPIC_SOS_MEDIA_UPLOADED, sosId.toString(), media);
  }

  @Test
  void targetedNotification_isKeyedByRecipient() {
    SosNotificationEvent e =
        new SosNotificationEvent("BID_ACCEPTED", other, "USER", sosId, "t", "b");
    producer.publishNotification(e);
    verify(kafkaTemplate).send(KafkaConfig.TOPIC_SOS_NOTIFICATION, other.toString(), e);
  }

  @Test
  void fanOutNotification_withoutRecipient_hasNullKey_insteadOfThrowing() {
    SosNotificationEvent e =
        new SosNotificationEvent("SOS_CREATED", null, "MECHANIC", sosId, "t", "b");
    producer.publishNotification(e);
    verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_SOS_NOTIFICATION), isNull(), eq(e));
  }

  @Test
  void pushFailure_goesToTheDeadLetterTopic_keyedByRecipient() {
    OneSignalPushFailedEvent e =
        new OneSignalPushFailedEvent(other, 500, "boom", LocalDateTime.now());
    producer.publishOneSignalPushFailed(e);
    verify(kafkaTemplate).send(KafkaConfig.TOPIC_SOS_DLQ, other.toString(), e);
  }
}
