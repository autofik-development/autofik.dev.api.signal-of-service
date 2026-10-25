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
import org.springframework.util.AntPathMatcher;

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

  private static final AntPathMatcher ANT = new AntPathMatcher(); // same matcher the broker uses

  // /topic/sos/{sosId}/location and /media; group 2 is the channel
  private static final Pattern SOS_TOPIC = Pattern.compile("^/topic/sos/([^/]+)/(.*)$");

  // Lazy: SosServiceImpl needs SimpMessagingTemplate, which the WebSocket configuration creates.
  // Injecting the service directly would close that loop and fail startup.
  private final ObjectProvider<SosService> sosService;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
      requireVisible(accessor.getUser(), accessor.getDestination());
    } else if (StompCommand.SEND.equals(accessor.getCommand())) {
      requireAppDestination(accessor.getDestination());
    }
    return message;
  }

  /**
   * A client may SEND only to the application handlers. Anything else ({@code /topic/**}, another
   * user's {@code /user/**} queue) would be relayed by the broker to its subscribers as if the
   * server had said it, letting one client forge a frame to another.
   */
  private void requireAppDestination(String destination) {
    if (destination == null || !destination.startsWith("/app/")) {
      throw new AccessDeniedException("Clients may only send to /app/**");
    }
  }

  private void requireVisible(Principal caller, String destination) {
    if (destination == null) return;
    // The simple broker treats a subscription destination as an Ant pattern, so "/topic/sos/**"
    // would receive every SOS's frames while sailing past the per-SOS check below (it has no
    // "/{id}/" segment to match). No client needs a wildcard: refuse them outright.
    if (ANT.isPattern(destination)) {
      throw new AccessDeniedException("Wildcard subscriptions are not allowed");
    }
    // Ant matching ignores empty segments, so "/topic//sos/{id}/location" reaches the same frames
    // as the canonical form; check the canonical form.
    Matcher matcher = SOS_TOPIC.matcher(destination.replaceAll("/{2,}", "/"));
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
    // The live position of the assigned mechanic is for the customer alone. Bidders and eligible
    // mechanics can read the ticket, but a subscription is never revoked, so anyone else let in
    // here keeps watching after they lose the bid or are released. The owner never changes.
    boolean allowed =
        "location".equals(matcher.group(2))
            ? sosService.getObject().isOwner(sosId, callerId)
            : sosService.getObject().canView(sosId, callerId);
    if (!allowed) {
      throw new AccessDeniedException("Not your SOS request");
    }
  }
}
