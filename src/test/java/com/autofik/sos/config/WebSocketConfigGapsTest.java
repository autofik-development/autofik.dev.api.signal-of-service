package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.security.MicroserviceTokenSdk;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import tools.jackson.databind.json.JsonMapper;

class WebSocketConfigGapsTest {

  private WebSocketConfig config;
  private HandshakeInterceptor interceptor;
  private DefaultHandshakeHandler handler;

  @BeforeEach
  void build() {
    config =
        new WebSocketConfig(
            mock(StompSubscriptionInterceptor.class),
            new MicroserviceTokenSdk(JsonMapper.builder().build()),
            mock(TaskScheduler.class));
    ReflectionTestUtils.setField(config, "allowedOrigins", "*");
    ReflectionTestUtils.setField(config, "devInsecureHandshake", true);
    StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
    StompWebSocketEndpointRegistration registration =
        mock(StompWebSocketEndpointRegistration.class, RETURNS_SELF);
    when(registry.addEndpoint("/ws")).thenReturn(registration);
    config.registerStompEndpoints(registry);
    ArgumentCaptor<HandshakeInterceptor> i = ArgumentCaptor.forClass(HandshakeInterceptor.class);
    verify(registration).addInterceptors(i.capture());
    interceptor = i.getValue();
    ArgumentCaptor<DefaultHandshakeHandler> h =
        ArgumentCaptor.forClass(DefaultHandshakeHandler.class);
    verify(registration).setHandshakeHandler(h.capture());
    handler = h.getValue();
  }

  private boolean handshake(String uri, Map<String, Object> attrs) throws Exception {
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getHeaders()).thenReturn(new HttpHeaders());
    when(request.getURI()).thenReturn(URI.create(uri));
    return interceptor.beforeHandshake(
        request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attrs);
  }

  @Test
  void devHandshake_rolesAndBadInput() throws Exception {
    String id = UUID.randomUUID().toString();
    Map<String, Object> asMechanic = new HashMap<>();

    assertThat(handshake("ws://h/ws?clientId=" + id + "&role=mechanic", asMechanic)).isTrue();
    assertThat(
            ((UsernamePasswordAuthenticationToken) asMechanic.get("callerAuthentication"))
                .getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_MECHANIC");
    assertThat(handshake("ws://h/ws?clientId=" + id, new HashMap<>())).isTrue();
    assertThat(handshake("ws://h/ws?clientId=%20", new HashMap<>())).isFalse();
    assertThat(handshake("ws://h/ws?clientId=not-a-uuid", new HashMap<>())).isFalse();
    assertThat(handshake("ws://h/ws", new HashMap<>())).isFalse();
  }

  @Test
  void afterHandshake_isANoOp_andThePrincipalComesFromTheStoredCaller() throws Exception {
    interceptor.afterHandshake(null, null, null, null);
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("who", null, List.of());
    Map<String, Object> attrs = Map.of("callerAuthentication", auth);

    Principal p = ReflectionTestUtils.invokeMethod(handler, "determineUser", null, null, attrs);

    assertThat(p).isSameAs(auth);
  }

  @Test
  void bothChannelsRunOnTheirOwnPools() {
    for (String f : new String[] {"inboundCore", "outboundCore"})
      ReflectionTestUtils.setField(config, f, 2);
    for (String f : new String[] {"inboundMax", "outboundMax"})
      ReflectionTestUtils.setField(config, f, 4);
    for (String f : new String[] {"inboundQueue", "outboundQueue"})
      ReflectionTestUtils.setField(config, f, 10);
    ChannelRegistration out = mock(ChannelRegistration.class);

    config.configureClientOutboundChannel(out);

    verify(out).executor(org.mockito.ArgumentMatchers.any());
    assertThat(config.webSocketOutboundExecutor().getThreadNamePrefix()).isEqualTo("ws-outbound-");
  }
}
