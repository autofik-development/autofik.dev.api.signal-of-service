package com.autofik.sos.kafka.consumer;

import com.autofik.sos.config.KafkaConfig;
import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SosMediaUploadedConsumer {

    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(
            topics = KafkaConfig.TOPIC_SOS_MEDIA_UPLOADED,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "mediaUploadedListenerContainerFactory"
    )
    public void consume(SosMediaUploadedEvent event) {
        try {
            messagingTemplate.convertAndSend("/topic/sos/" + event.sosId() + "/media", event);
        } catch (Exception e) {
            log.error("Failed to relay media upload event to WebSocket sosId={} mediaId={}: {}",
                    event.sosId(), event.mediaId(), e.getMessage());
        }
    }
}
