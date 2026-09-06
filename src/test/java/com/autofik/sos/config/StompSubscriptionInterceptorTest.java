package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.autofik.sos.service.SosService;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

/**
 * The per-SOS topics are broadcast destinations, so this interceptor is the only thing between a
 * guessed sosId and a live view of a mechanic driving.
 */
class StompSubscriptionInterceptorTest {

  private final SosService sosService = mock(SosService.class);
  private StompSubscriptionInterceptor interceptor;

  private final UUID sosId = UUID.randomUUID();
  private final UUID callerId = UUID.randomUUID();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ObjectProvider<SosService> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(sosService);
    interceptor = new StompSubscriptionInterceptor(provider);
  }

  private Message<byte[]> subscribe(String destination, Principal caller) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    accessor.setUser(caller);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private Principal principal(UUID id) {
    return id::toString;
  }

  @Test
  void participantMaySubscribeToSosTopic() {
    when(sosService.canView(sosId, callerId)).thenReturn(true);

    Message<byte[]> message = subscribe("/topic/sos/" + sosId + "/location", principal(callerId));

    assertThat(interceptor.preSend(message, null)).isSameAs(message);
  }

  @Test
  void outsiderMayNotSubscribeToSosTopic() {
    when(sosService.canView(sosId, callerId)).thenReturn(false);

    Message<byte[]> message = subscribe("/topic/sos/" + sosId + "/location", principal(callerId));

    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void mediaTopicIsGuardedByTheSameRule() {
    when(sosService.canView(sosId, callerId)).thenReturn(false);

    Message<byte[]> message = subscribe("/topic/sos/" + sosId + "/media", principal(callerId));

    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  // Private queues are routed by the principal, so Spring already scopes them per session.
  @Test
  void userQueuesPassThroughUnchecked() {
    Message<byte[]> message = subscribe("/user/queue/sos-updates", principal(callerId));

    assertThat(interceptor.preSend(message, null)).isSameAs(message);
    verify(sosService, never()).canView(any(), any());
  }

  @Test
  void unauthenticatedSubscriptionIsRejected() {
    Message<byte[]> message = subscribe("/topic/sos/" + sosId + "/location", null);

    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void malformedSosIdIsRejectedRatherThanIgnored() {
    Message<byte[]> message = subscribe("/topic/sos/not-a-uuid/location", principal(callerId));

    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(AccessDeniedException.class);
    verify(sosService, never()).canView(any(), any());
  }
}
