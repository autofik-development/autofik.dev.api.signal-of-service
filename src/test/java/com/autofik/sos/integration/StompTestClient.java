package com.autofik.sos.integration;

import java.util.concurrent.TimeUnit;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** A real STOMP-over-WebSocket client, connecting the way the mobile apps do. */
final class StompTestClient {

  private final WebSocketStompClient client;

  StompTestClient() {
    client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new JacksonJsonMessageConverter());
    // Matches the server's 10 s heartbeat so an idle connection is not dropped by the client.
    // Heartbeats need a scheduler on the client side; one small shared pool serves every session.
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("it-stomp-heartbeat-");
    scheduler.initialize();
    client.setTaskScheduler(scheduler);
    client.setDefaultHeartbeat(new long[] {10_000, 10_000});
  }

  /**
   * Connects with the identity header the gateway would write on the upgrade; {@code contextHeader}
   * is "user-context" or "mechanic-context".
   */
  StompSession connect(int port, String contextHeader, String contextJson) throws Exception {
    WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
    handshake.add(contextHeader, contextJson);
    return client
        .connectAsync(
            "ws://localhost:" + port + "/ws",
            handshake,
            new StompHeaders(),
            new StompSessionHandlerAdapter() {})
        .get(10, TimeUnit.SECONDS);
  }
}
