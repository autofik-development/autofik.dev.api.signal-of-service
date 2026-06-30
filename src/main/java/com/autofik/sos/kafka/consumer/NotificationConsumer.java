package com.autofik.sos.kafka.consumer;

import com.autofik.sos.config.KafkaConfig;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.service.OneSignalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationConsumer {

    private final OneSignalService oneSignalService;

    @KafkaListener(
            topics = KafkaConfig.TOPIC_SOS_NOTIFICATION,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationListenerContainerFactory"
    )
    public void consume(SosNotificationEvent event) {
        // SOS_CREATED fan-out has no specific recipient — already handled by WebSocket fan-out
        if (event.recipientId() == null) return;

        try {
            oneSignalService.send(
                    event.recipientId(),
                    event.title(),
                    event.body(),
                    Map.of("type", event.type(), "sosId", event.sosId().toString())
            );
        } catch (Exception e) {
            log.warn("Notification delivery failed type={} recipient={}: {}", event.type(), event.recipientId(), e.getMessage());
        }
    }
}
