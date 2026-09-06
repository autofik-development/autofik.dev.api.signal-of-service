package com.autofik.sos.config;

import com.autofik.sos.service.SosService;
import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Authorizes STOMP subscriptions.
 *
 * <p>Proving who connected only protects {@code /user/queue/**}. The per-SOS topics are broadcast
 * destinations, so before this a guessed {@code sosId} was enough to watch a mechanic drive, live,
 * with coordinates — or to read the photos attached to someone else's breakdown.
 */
@Component
@RequiredArgsConstructor
public class StompSubscriptionInterceptor implements ChannelInterceptor {

  // /topic/sos/{sosId}/location and /media
  private static final Pattern SOS_TOPIC = Pattern.compile("^/topic/sos/([^/]+)/.*$");

  // Lazy: SosServiceImpl needs SimpMessagingTemplate, which the WebSocket configuration creates.
  // Injecting the service directly would close that loop and fail startup.
  private final ObjectProvider<SosService> sosService;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
      requireVisible(accessor.getUser(), accessor.getDestination());
    }
    return message;
  }

  private void requireVisible(Principal caller, String destination) {
    if (destination == null) return;
    Matcher matcher = SOS_TOPIC.matcher(destination);
    if (!matcher.matches()) return;

    if (caller == null) throw new AccessDeniedException("Unauthenticated subscription");
    UUID sosId;
    UUID callerId;
    try {
      sosId = UUID.fromString(matcher.group(1));
      callerId = UUID.fromString(caller.getName());
    } catch (IllegalArgumentException malformedUuid) {
      throw new AccessDeniedException("Malformed subscription destination");
    }
    if (!sosService.getObject().canView(sosId, callerId)) {
      throw new AccessDeniedException("Not your SOS request");
    }
  }
}
