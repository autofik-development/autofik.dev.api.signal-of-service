package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;

/**
 * Hostile STOMP over a real socket, written by hand so nothing sanitises it the way a STOMP client
 * library would. The server may reject, ignore or hang up on any of this; what it must not do is
 * fall over, or stop serving the next honest client.
 */
class StompFrameAbuseIntegrationTest extends IntegrationTestBase {

  private static final String NUL = "\u0000";

  /** A raw WebSocket that records everything the server sends back and whether it hung up. */
  private static final class RawSocket implements WebSocket.Listener {
    final List<String> received = new CopyOnWriteArrayList<>();
    final AtomicBoolean closed = new AtomicBoolean();
    WebSocket socket;

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      received.add(data.toString());
      ws.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
      closed.set(true);
      return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
      closed.set(true);
    }

    boolean saw(String command) {
      return received.stream().anyMatch(f -> f.startsWith(command));
    }
  }

  private RawSocket open(String contextHeader, String contextJson) throws Exception {
    RawSocket listener = new RawSocket();
    listener.socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .header(contextHeader, contextJson)
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener)
            .get();
    return listener;
  }

  private RawSocket openAsMechanic() throws Exception {
    return open("mechanic-context", mechanicContext(UUID.randomUUID()));
  }

  private void send(RawSocket s, String frame) throws Exception {
    try {
      s.socket.sendText(frame, true).get();
    } catch (java.util.concurrent.ExecutionException e) {
      // The server may hang up while a refused frame is still being written ("closed output").
      // That is the outcome these tests want; anything else is a real failure.
      if (!(e.getCause() instanceof java.io.IOException)) throw e;
    }
  }

  private void pause() throws InterruptedException {
    Thread.sleep(500); // let the server react (or hang up) before looking
  }

  /** After any abuse: the app answers, and an ordinary client can still connect and subscribe. */
  private void assertStillServing() throws Exception {
    assertThat(healthProbeMillis(Duration.ofSeconds(3)))
        .as("/healthy after abuse")
        .isGreaterThan(-1);
    StompSession honest =
        new StompTestClient().connect(port, "user-context", userContext(UUID.randomUUID()));
    assertThat(honest.isConnected()).as("an honest client can still connect").isTrue();
    honest.disconnect();
  }

  @Test
  void garbageAndOutOfOrderFrames_areRejectedWithoutHarmingTheServer() throws Exception {
    List<String> frames =
        List.of(
            "GARBAGE\n\n" + NUL,
            "\n\n\n" + NUL,
            "CONNECT" + NUL, // no header block at all
            "CONNECT\naccept-version:9.9\n\n" + NUL, // a version nobody speaks
            // Commands that are only valid after CONNECT, sent first.
            "SEND\ndestination:/app/mechanic/location\n\n{\"latitude\":11.5,\"longitude\":104.9}"
                + NUL,
            "SUBSCRIBE\nid:1\ndestination:/user/queue/sos-updates\n\n" + NUL,
            "DISCONNECT\n\n" + NUL,
            // Required headers missing.
            "CONNECT\naccept-version:1.2\n\n" + NUL + "SUBSCRIBE\nid:1\n\n" + NUL,
            "CONNECT\naccept-version:1.2\n\n" + NUL + "SEND\n\nbody" + NUL,
            // Header and content-length lies.
            "SEND\ndestination:/app/x\ncontent-length:999999\n\nshort" + NUL,
            "SEND\ndestination:/app/x\ncontent-length:-1\n\nx" + NUL,
            // Unknown command, and a command with a very long name.
            "NOPE\n\n" + NUL,
            "A".repeat(70_000) + "\n\n" + NUL);

    for (String frame : frames) {
      RawSocket s = openAsMechanic();
      send(s, frame);
      pause();
      s.socket.abort();
    }

    assertStillServing();
  }

  @Test
  void oversizedFramesAndHeaderFloods_areRefused() throws Exception {
    // 2 MB body, far past any sane location ping.
    RawSocket big = openAsMechanic();
    send(big, "SEND\ndestination:/app/mechanic/location\n\n" + "x".repeat(2 * 1024 * 1024) + NUL);
    pause();
    big.socket.abort();

    // Twenty thousand headers on one frame.
    StringBuilder many = new StringBuilder("CONNECT\naccept-version:1.2\n");
    for (int i = 0; i < 20_000; i++) many.append("h").append(i).append(":v\n");
    many.append("\n").append(NUL);
    RawSocket flood = openAsMechanic();
    send(flood, many.toString());
    pause();
    flood.socket.abort();

    // Binary frames, which STOMP-over-text-WebSocket never expects.
    RawSocket binary = openAsMechanic();
    binary.socket.sendBinary(ByteBuffer.wrap(new byte[] {0, 1, 2, (byte) 0xff, 0}), true).get();
    pause();
    binary.socket.abort();

    assertStillServing();
  }

  /**
   * The wildcard subscription fix, checked end to end over the wire rather than on the interceptor
   * in isolation: asking for every SOS's live location must not be acknowledged.
   */
  @Test
  void wildcardSubscriptionToEverySos_isNotAcknowledged() throws Exception {
    for (String destination :
        List.of("/topic/sos/**", "/topic/**", "/topic/sos/*/location", "/topic//sos/x/location")) {
      RawSocket s = open("user-context", userContext(UUID.randomUUID()));
      send(s, "CONNECT\naccept-version:1.2\n\n" + NUL);
      pause();
      assertThat(s.saw("CONNECTED")).as("handshake completes for %s", destination).isTrue();

      // A receipt header makes the server confirm an accepted subscription, so silence or ERROR
      // is distinguishable from success.
      send(s, "SUBSCRIBE\nid:sub-1\nreceipt:r-1\ndestination:" + destination + "\n\n" + NUL);
      pause();

      assertThat(s.saw("RECEIPT"))
          .as("server acknowledged a subscription to %s", destination)
          .isFalse();
      s.socket.abort();
    }
  }

  /** A client that connects and goes silent must not be held open forever. */
  @Test
  void silentConnection_isEventuallyDropped_notHeldForever() throws Exception {
    RawSocket s = openAsMechanic();
    send(s, "CONNECT\naccept-version:1.2\nheart-beat:5000,0\n\n" + NUL);
    pause();
    assertThat(s.closed.get()).isFalse();

    // The client promised a heartbeat every 5 s and then sends none, so the server should give up
    // on it within a few intervals.
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
    while (!s.closed.get() && System.currentTimeMillis() < deadline) Thread.sleep(500);

    System.out.println("SILENT closedByServer=" + s.closed.get());
    assertThat(s.closed.get())
        .as("server should drop a client that promised heartbeats and sent none")
        .isTrue();
    s.socket.abort();
    assertStillServing();
  }
}
