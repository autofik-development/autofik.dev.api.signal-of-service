package com.autofik.sos.kafka.consumer;

import com.autofik.sos.config.KafkaConfig;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MechanicLocationConsumer{

    private final SimpMessagingTemplate messagingTemplate;
    @KafkaListener(
        topics = KafkaConfig.TOPIC_MECHANIC_LOCATION_UPDATED,
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(MechanicLocationEvent event){
        messagingTemplate.convertAndSend(
            "/topic/sos/" + event.sosId() + "/location", event
        );
    }

}