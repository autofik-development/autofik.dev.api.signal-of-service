package com.autofik.sos.redis;

import com.autofik.sos.dto.event.MechanicLocationEvent;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import java.nio.charset.StandardCharsets;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MechanicLocationRedisListener implements MessageListener {

    public static final String CHANNEL_PREFIX = "mechanic:loc:";

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String sosId = channel.substring(CHANNEL_PREFIX.length());
            MechanicLocationEvent event = objectMapper.readValue(message.getBody(), MechanicLocationEvent.class);
            messagingTemplate.convertAndSend("/topic/sos/" + sosId + "/location", event);
        } catch (Exception e) {
            log.error("Failed to relay Redis location message to WebSocket: {}", e.getMessage());
        }
    }
}
