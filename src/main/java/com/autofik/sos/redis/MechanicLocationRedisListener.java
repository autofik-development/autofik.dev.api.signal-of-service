package com.autofik.sos.redis;

import com.autofik.sos.dto.event.MechanicLocationEvent;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis Pub/Sub message listener that intercepts real-time mechanic location updates and bridges
 * them directly to active client frontend clients via WebSockets.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MechanicLocationRedisListener implements MessageListener {

  /**
   * Channel prefix used in Redis to publish mechanic locations (e.g., "mechanic:loc:123" where 123
   * is the sosId).
   */
  public static final String CHANNEL_PREFIX = "mechanic:loc:";

  private final SimpMessagingTemplate messagingTemplate;
  private final ObjectMapper objectMapper;

  /**
   * Callback triggered whenever a new message arrives on a subscribed Redis channel. Extracts the
   * specific SOS session context from the channel name and pushes the coordinate update to the
   * corresponding WebSocket topic.
   *
   * @param message the Redis message payload containing channel data and body
   * @param pattern the pattern matching the channel subscription (if applicable)
   */
  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      // Extract the channel name to determine the specific SOS session context
      String channel = new String(message.getChannel(), StandardCharsets.UTF_8);

      // Parse the sosId directly out of the channel string (e.g., "mechanic:loc:99" -> "99")
      String sosId = channel.substring(CHANNEL_PREFIX.length());

      // Deserialize the raw JSON message body into a structured domain event
      MechanicLocationEvent event =
          objectMapper.readValue(message.getBody(), MechanicLocationEvent.class);

      // Forward the event to the active client/mechanic WebSocket subscriber channel
      messagingTemplate.convertAndSend(
          "/topic/sos/" + sosId + "/location", event.withCambodianTimestamp());
    } catch (Exception e) {
      // Log serialization or routing issues without interrupting the Redis network listener thread
      log.error("Failed to relay Redis location message to WebSocket: {}", e.getMessage());
    }
  }
}
