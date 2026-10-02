package com.autofik.sos.config;

import com.autofik.sos.security.CallerRole;
import com.autofik.sos.security.CurrentUser;
import com.autofik.sos.security.MicroserviceTokenSdk;
import com.autofik.sos.security.UserContextRejectedException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private static final String CALLER_ATTRIBUTE = "callerAuthentication";

  @Value("${websocket.allowed-origins:*}")
  private String allowedOrigins;

  /** See SecurityConfig — local-development switch, never enabled anywhere else. */
  @Value("${websocket.dev-insecure-handshake:false}")
  private boolean devInsecureHandshake;

  private final StompSubscriptionInterceptor subscriptionInterceptor;

  private final MicroserviceTokenSdk tokenSdk;

  @Qualifier("heartbeatTaskScheduler")
  private final TaskScheduler heartbeatTaskScheduler;

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint("/ws")
        .setAllowedOriginPatterns(allowedOrigins)
        .addInterceptors(
            new HandshakeInterceptor() {
              @Override
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler wsHandler,
                  Map<String, Object> attributes) {
                // Same contract as the REST filter — the gateway writes user-context on the
                // upgrade too. The old ?clientId= is gone: it was chosen by the caller, so
                // connecting as someone else was enough to read their private queue.
                Optional<CurrentUser> caller;
                try {
                  caller = tokenSdk.resolveCurrentUser(name -> request.getHeaders().getFirst(name));
                } catch (UserContextRejectedException rejected) {
                  log.warn("Rejected WebSocket upgrade: {}", rejected.getMessage());
                  return false; // 403 on the upgrade
                }

                // Local development only. A browser cannot set headers on a WebSocket
                // handshake, so test-ws.html has no way to identify itself; this lets it
                // name itself instead. That is precisely the impersonation hole the header
                // contract exists to close, which is why it is off unless asked for and
                // logs a warning at startup when it is not.
                if (caller.isEmpty() && devInsecureHandshake) {
                  caller = devCallerFromQuery(request);
                }

                if (caller.isEmpty()) return false;

                attributes.put(
                    CALLER_ATTRIBUTE,
                    new UsernamePasswordAuthenticationToken(
                        caller.get().id(),
                        null,
                        List.of(new SimpleGrantedAuthority(caller.get().authority()))));
                return true;
              }

              @Override
              public void afterHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler wsHandler,
                  Exception exception) {}
            })
        .setHandshakeHandler(
            new DefaultHandshakeHandler() {
              @Override
              protected Principal determineUser(
                  ServerHttpRequest request,
                  WebSocketHandler wsHandler,
                  Map<String, Object> attributes) {
                // getName() is the UUID string, which is what convertAndSendToUser routes on.
                return (Principal) attributes.get(CALLER_ATTRIBUTE);
              }
            });
  }

  /**
   * The caller-supplied {@code ?clientId=&role=} fallback, for the local test console only. See
   * SecurityConfig — this is off unless websocket.dev-insecure-handshake is explicitly set.
   */
  private Optional<CurrentUser> devCallerFromQuery(ServerHttpRequest request) {
    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
    String clientId = query.getFirst("clientId");
    if (clientId == null || clientId.isBlank()) {
      return Optional.empty();
    }
    try {
      UUID id = UUID.fromString(clientId.trim());
      CallerRole role =
          "mechanic".equalsIgnoreCase(query.getFirst("role"))
              ? CallerRole.MECHANIC
              : CallerRole.USER;
      log.warn("Insecure dev handshake: accepting caller-supplied clientId={}", clientId);
      return Optional.of(new CurrentUser(id, role, null, null));
    } catch (IllegalArgumentException malformedUuid) {
      return Optional.empty();
    }
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry config) {
    config.setApplicationDestinationPrefixes("/app");
    config
        .enableSimpleBroker("/topic", "/queue")
        .setTaskScheduler(heartbeatTaskScheduler)
        .setHeartbeatValue(new long[] {10_000, 10_000});
  }

  // Pool sizes are properties so they can be tuned without a code change; the defaults are what
  // the comments below were written against.
  @Value("${websocket.inbound.core-pool-size:32}")
  private int inboundCore;

  @Value("${websocket.inbound.max-pool-size:64}")
  private int inboundMax;

  @Value("${websocket.inbound.queue-capacity:1000}")
  private int inboundQueue;

  @Value("${websocket.outbound.core-pool-size:8}")
  private int outboundCore;

  @Value("${websocket.outbound.max-pool-size:16}")
  private int outboundMax;

  @Value("${websocket.outbound.queue-capacity:1000}")
  private int outboundQueue;

  // Threads beyond the core size are only created once the queue is full, so an unbounded queue
  // (the default) means the pool never grows past its core size and a flood piles up in memory
  // instead. A bounded queue lets the pool grow; when even that is full, the sender's own
  // connection thread runs the frame (CallerRuns), which slows that one client down instead of
  // dropping frames or queueing without limit.
  private static ThreadPoolTaskExecutor channelPool(
      String prefix, int core, int max, int queueCapacity) {
    ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
    pool.setThreadNamePrefix(prefix);
    pool.setCorePoolSize(core);
    pool.setMaxPoolSize(max);
    pool.setQueueCapacity(queueCapacity);
    pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    return pool;
  }

  // Beans so Spring starts and stops them with the context, but kept out of autowiring: nothing
  // else should pick up a TaskExecutor by type and end up sharing a WebSocket pool.
  @Bean(autowireCandidate = false)
  public ThreadPoolTaskExecutor webSocketInboundExecutor() {
    return channelPool("ws-inbound-", inboundCore, inboundMax, inboundQueue);
  }

  @Bean(autowireCandidate = false)
  public ThreadPoolTaskExecutor webSocketOutboundExecutor() {
    return channelPool("ws-outbound-", outboundCore, outboundMax, outboundQueue);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(subscriptionInterceptor);
    // Location pings land here, and a ping can pay for a Google Directions call once per 30s
    // per SOS. A small pool would let a handful of in-flight route refreshes stall every other
    // mechanic's ping behind them — the HTTP path never showed this because Tomcat has ~200
    // threads to absorb it.
    // Set explicitly rather than through registration.taskExecutor(): an integration test found
    // that setting was not taking effect and the channel was running on the one-thread heartbeat
    // scheduler (largestPool=1), so a single slow call stalled every frame.
    // ponytail: sized pool in front of blocking I/O. Move the route refresh off this thread
    // if the fleet outgrows it.
    registration.executor(webSocketInboundExecutor());
  }

  @Override
  public void configureClientOutboundChannel(ChannelRegistration registration) {
    // Server-to-client pushes (SOS updates, live location). Same reasoning: not the shared
    // single-thread scheduler.
    registration.executor(webSocketOutboundExecutor());
  }
}
