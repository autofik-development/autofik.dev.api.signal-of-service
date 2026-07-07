package com.autofik.sos.kafka.consumer;

import com.autofik.sos.config.KafkaConfig;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.service.OneSignalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kafka consumer responsible for processing notification events.
 * It listens to the designated SOS notification topic and dispatches push notifications
 * to target recipients via the OneSignal push service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationConsumer {

    private final OneSignalService oneSignalService;

    /**
     * Consumes incoming notification events from Kafka.
     * Filters out broadcast events and routes targeted notifications to individual users.
     *
     * @param event the incoming payload containing notification details
     */
    @KafkaListener(
            topics = KafkaConfig.TOPIC_SOS_NOTIFICATION,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationListenerContainerFactory"
    )
    public void consume(SosNotificationEvent event) {
        // SOS_CREATED fan-out has no specific recipient — already handled by WebSocket fan-out
        // We safely bypass execution here to avoid sending individual duplicate push alerts.
        if (event.recipientId() == null) return;

        try {
            // Forward targeted notification payloads directly to the OneSignal external push service
            oneSignalService.send(
                    event.recipientId(),
                    event.title(),
                    event.body(),
                    Map.of("type", event.type(), "sosId", event.sosId().toString())
            );
        } catch (Exception e) {
            // Prevent Kafka consumer loop blocking or accidental retries due to third-party API issues
            log.warn("Notification delivery failed type={} recipient={}: {}", event.type(), event.recipientId(), e.getMessage());
        }
    }
}
