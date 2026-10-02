package com.autofik.sos.kafka.consumer;

import com.autofik.sos.config.KafkaConfig;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

// Fallback consumer: only receives location events when Redis pub/sub is unavailable.
// Under normal operation MechanicLocationRedisListener delivers to WebSocket with lower latency.
@Component
@RequiredArgsConstructor
@Slf4j
public class MechanicLocationConsumer {

  private final SimpMessagingTemplate messagingTemplate;

  @KafkaListener(
      topics = KafkaConfig.TOPIC_MECHANIC_LOCATION_UPDATED,
      groupId = "${spring.kafka.consumer.group-id}")
  public void consume(MechanicLocationEvent event) {
    // A null record (tombstone, or a payload that failed to deserialize) has nothing to relay.
    // Throwing here would only make the container retry the same record.
    if (event == null) {
      log.warn("Ignoring null location record");
      return;
    }
    try {
      messagingTemplate.convertAndSend("/topic/sos/" + event.sosId() + "/location", event);
    } catch (Exception e) {
      log.error(
          "Failed to relay Kafka location event to WebSocket sosId={}: {}",
          event.sosId(),
          e.getMessage());
    }
  }
}
