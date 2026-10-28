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
  void ownerMaySubscribeToLocation() {
    when(sosService.isOwner(sosId, callerId)).thenReturn(true);

    Message<byte[]> message = subscribe("/topic/sos/" + sosId + "/location", principal(callerId));

    assertThat(interceptor.preSend(message, null)).isSameAs(message);
  }

  // canView passes bidders and eligible mechanics; the live position is for the owner alone.
  @Test
  void bidderWhoCanViewTheSos_mayNotSubscribeToLocation() {
    when(sosService.canView(sosId, callerId)).thenReturn(true);
    when(sosService.isOwner(sosId, callerId)).thenReturn(false);

    Message<byte[]> message = subscribe("/topic/sos/" + sosId + "/location", principal(callerId));

    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void mediaTopic_stillAllowsAnyoneWhoCanView() {
    when(sosService.canView(sosId, callerId)).thenReturn(true);

    Message<byte[]> message = subscribe("/topic/sos/" + sosId + "/media", principal(callerId));

    assertThat(interceptor.preSend(message, null)).isSameAs(message);
    verify(sosService, never()).isOwner(any(), any());
  }

  /**
   * Found by fuzzing: the simple broker treats a subscription as an Ant pattern, and these have no
   * "/{id}/" segment for the per-SOS check to match, so they used to be waved through.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "/topic/sos/**",
        "/topic/sos/*",
        "/topic/sos/*/location",
        "/topic/**",
        "/topic/sos/?/location",
        "/topic//sos/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/location"
      })
  void wildcardAndNonCanonicalDestinationsAreRefused(String destination) {
    when(sosService.canView(any(), any())).thenReturn(false);

    assertThatThrownBy(() -> interceptor.preSend(subscribe(destination, principal(callerId)), null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void outsiderMayNotSubscribeToSosTopic() {
    when(sosService.isOwner(sosId, callerId)).thenReturn(false);

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
    verify(sosService, never()).isOwner(any(), any());
  }

  private Message<byte[]> sendTo(String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination(destination);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  @Test
  void clientMaySendToAppDestinations() {
    Message<byte[]> message = sendTo("/app/bids");

    assertThat(interceptor.preSend(message, null)).isSameAs(message);
  }

  /** The broker would relay these to subscribers as if the server had said it. */
  @Test
  void clientCannotSendOutsideApp() {
    for (String destination :
        new String[] {
          "/topic/sos/" + UUID.randomUUID() + "/location",
          "/user/" + UUID.randomUUID() + "/queue/sos-updates",
          "/queue/errors",
          "/application/x",
          "app/bids",
          null
        }) {
      assertThatThrownBy(() -> interceptor.preSend(sendTo(destination), null))
          .as("SEND to %s", destination)
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void subscriptionWithoutADestination_isPassedThrough() {
    Message<byte[]> message = subscribe(null, principal(callerId));
    assertThat(interceptor.preSend(message, null)).isSameAs(message);
  }

  @Test
  void anonymousSubscriptionToASosTopic_isRefused() {
    assertThatThrownBy(
            () -> interceptor.preSend(subscribe("/topic/sos/" + sosId + "/location", null), null))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void otherFrames_arePassedThrough_andSendsOutsideAppAreRefused() {
    StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
    connect.setLeaveMutable(true);
    Message<byte[]> connectFrame =
        MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());
    assertThat(interceptor.preSend(connectFrame, null)).isSameAs(connectFrame);

    for (String destination : new String[] {"/topic/x", null}) {
      StompHeaderAccessor send = StompHeaderAccessor.create(StompCommand.SEND);
      send.setDestination(destination);
      send.setLeaveMutable(true);
      Message<byte[]> frame = MessageBuilder.createMessage(new byte[0], send.getMessageHeaders());
      assertThatThrownBy(() -> interceptor.preSend(frame, null))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    StompHeaderAccessor ok = StompHeaderAccessor.create(StompCommand.SEND);
    ok.setDestination("/app/bids");
    ok.setLeaveMutable(true);
    Message<byte[]> okFrame = MessageBuilder.createMessage(new byte[0], ok.getMessageHeaders());
    assertThat(interceptor.preSend(okFrame, null)).isSameAs(okFrame);
  }
}
