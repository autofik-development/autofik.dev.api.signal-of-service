package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.security.MicroserviceTokenSdk;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The WebSocket upgrade is where a connection gets its identity, so the accept/reject decision is
 * pinned here without a server: the interceptor is taken from the registration the config builds.
 */
class WebSocketHandshakeTest {

  private static final UUID ID = UUID.randomUUID();

  private WebSocketConfig config;
  private HandshakeInterceptor interceptor;

  @BeforeEach
  void build() {
    config =
        new WebSocketConfig(
            mock(StompSubscriptionInterceptor.class),
            new MicroserviceTokenSdk(JsonMapper.builder().build()),
            mock(TaskScheduler.class));
    ReflectionTestUtils.setField(config, "allowedOrigins", "*");
    interceptor = capture(config);
  }

  private static HandshakeInterceptor capture(WebSocketConfig c) {
    StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
    StompWebSocketEndpointRegistration registration =
        mock(StompWebSocketEndpointRegistration.class, RETURNS_SELF);
    when(registry.addEndpoint("/ws")).thenReturn(registration);
    c.registerStompEndpoints(registry);
    ArgumentCaptor<HandshakeInterceptor> captor =
        ArgumentCaptor.forClass(HandshakeInterceptor.class);
    verify(registration).addInterceptors(captor.capture());
    return captor.getValue();
  }

  private boolean handshake(String uri, Map<String, String> headers, Map<String, Object> attributes)
      throws Exception {
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    HttpHeaders h = new HttpHeaders();
    headers.forEach(h::add);
    when(request.getHeaders()).thenReturn(h);
    when(request.getURI()).thenReturn(URI.create(uri));
    return interceptor.beforeHandshake(
        request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
  }

  private static String ctx(String block, String role, boolean verified) {
    return "{\""
        + block
        + "\":{\"uuid\":\""
        + ID
        + "\","
        + (role == null ? "" : "\"role\":\"" + role + "\",")
        + "\"otpVerify\":"
        + verified
        + ",\"profileVerify\":true,\"tokenStatus\":\"active\",\"isLogout\":false}}";
  }

  @Test
  void userContext_isAccepted_andTheCallerIsStoredForThePrincipal() throws Exception {
    Map<String, Object> attrs = new HashMap<>();

    boolean ok = handshake("ws://h/ws", Map.of("user-context", ctx("user", "USER", true)), attrs);

    assertThat(ok).isTrue();
    Authentication auth = (Authentication) attrs.get("callerAuthentication");
    assertThat(auth.getPrincipal()).isEqualTo(ID);
    assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
  }

  @Test
  void mechanicContext_isAccepted_asAMechanic() throws Exception {
    Map<String, Object> attrs = new HashMap<>();

    boolean ok =
        handshake("ws://h/ws", Map.of("mechanic-context", ctx("mechanic", null, true)), attrs);

    assertThat(ok).isTrue();
    assertThat(((Authentication) attrs.get("callerAuthentication")).getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_MECHANIC");
  }

  @Test
  void noIdentity_isRefused() throws Exception {
    assertThat(handshake("ws://h/ws", Map.of(), new HashMap<>())).isFalse();
  }

  // The internal gateway refuses unverified callers; the handshake does not check the flags again.
  @Test
  void otpFlagFalse_isAccepted() throws Exception {
    assertThat(
            handshake(
                "ws://h/ws", Map.of("user-context", ctx("user", "USER", false)), new HashMap<>()))
        .isTrue();
  }

  @Test
  void aMechanicContextClaimingTheDriverRole_isRefused() throws Exception {
    assertThat(
            handshake(
                "ws://h/ws",
                Map.of("mechanic-context", ctx("mechanic", "USER", true)),
                new HashMap<>()))
        .isFalse();
  }

  @Test
  void garbageContext_isRefused() throws Exception {
    assertThat(handshake("ws://h/ws", Map.of("user-context", "{not json"), new HashMap<>()))
        .isFalse();
  }

  @Test
  void clientIdInTheQueryString_isIgnored_unlessTheDevSwitchIsOn() throws Exception {
    String uri = "ws://h/ws?clientId=" + ID + "&role=mechanic";

    assertThat(handshake(uri, Map.of(), new HashMap<>())).as("dev switch off (default)").isFalse();

    ReflectionTestUtils.setField(config, "devInsecureHandshake", true);
    interceptor = capture(config);
    Map<String, Object> attrs = new HashMap<>();
    assertThat(handshake(uri, Map.of(), attrs)).as("dev switch on").isTrue();
    assertThat(((Authentication) attrs.get("callerAuthentication")).getPrincipal()).isEqualTo(ID);
    assertThat(handshake("ws://h/ws?clientId=not-a-uuid", Map.of(), new HashMap<>())).isFalse();
    assertThat(handshake("ws://h/ws?clientId=", Map.of(), new HashMap<>())).isFalse();
  }

  @Test
  void aRealHeaderWins_overTheDevQueryString() throws Exception {
    ReflectionTestUtils.setField(config, "devInsecureHandshake", true);
    interceptor = capture(config);
    UUID other = UUID.randomUUID();
    Map<String, Object> attrs = new HashMap<>();

    handshake(
        "ws://h/ws?clientId=" + other, Map.of("user-context", ctx("user", "USER", true)), attrs);

    assertThat(((Authentication) attrs.get("callerAuthentication")).getPrincipal()).isEqualTo(ID);
  }

  @Test
  void brokerAndChannelPools_areConfigured() {
    MessageBrokerRegistry broker = mock(MessageBrokerRegistry.class, RETURNS_SELF);
    var simple =
        mock(
            org.springframework.messaging.simp.config.SimpleBrokerRegistration.class, RETURNS_SELF);
    when(broker.enableSimpleBroker("/topic", "/queue")).thenReturn(simple);
    config.configureMessageBroker(broker);
    verify(broker).setApplicationDestinationPrefixes("/app");
    verify(simple).setHeartbeatValue(new long[] {10_000, 10_000});

    ReflectionTestUtils.setField(config, "inboundCore", 4);
    ReflectionTestUtils.setField(config, "inboundMax", 8);
    ReflectionTestUtils.setField(config, "inboundQueue", 16);
    var pool = config.webSocketInboundExecutor();
    assertThat(pool.getCorePoolSize()).isEqualTo(4);
    assertThat(pool.getMaxPoolSize()).isEqualTo(8);
    assertThat(pool.getQueueCapacity()).isEqualTo(16);
    pool.initialize();
    assertThat(pool.getThreadPoolExecutor().getRejectedExecutionHandler())
        .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy.class);
    pool.shutdown();

    ChannelRegistration reg = mock(ChannelRegistration.class, RETURNS_SELF);
    config.configureClientInboundChannel(reg);
    verify(reg).executor(any());
  }
}
