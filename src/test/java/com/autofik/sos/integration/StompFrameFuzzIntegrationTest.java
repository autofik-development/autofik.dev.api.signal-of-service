package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.user.SimpUserRegistry;

/**
 * Raw STOMP frames over a real WebSocket: random commands, headers, destinations and bodies, many
 * of them malformed on purpose. Looks for what fuzzing can actually show: the server stops
 * answering, leaks state after the clients are gone, lets a stranger receive someone else's SOS
 * location, or stops serving an honest client afterwards.
 *
 * <p>Deterministic. The run is driven by {@code -Dfuzz.seed} (default fixed) and {@code
 * -Dfuzz.frames}; on any failure the seed and the offending frame are printed as escaped text, so a
 * failure can be replayed by hand: open a socket with a {@code user-context} header and send the
 * logged frame.
 */
class StompFrameFuzzIntegrationTest extends FlowTestSupport {

  private static final long SEED = Long.getLong("fuzz.seed", 20261003L);
  private static final int FRAMES = Integer.getInteger("fuzz.frames", 400);
  private static final String NUL = "\u0000";

  @Autowired private SimpUserRegistry users;

  /** One raw socket; remembers every text frame the server sent and whether it closed. */
  private static final class Raw {
    final WebSocket socket;
    final List<String> received = new CopyOnWriteArrayList<>();
    volatile boolean closed;

    Raw(URI uri, String header, String value) throws Exception {
      StringBuilder partial = new StringBuilder();
      socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .header(header, value)
              .connectTimeout(Duration.ofSeconds(5))
              .buildAsync(
                  uri,
                  new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket ws) {
                      ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence d, boolean last) {
                      partial.append(d);
                      if (last) {
                        received.add(partial.toString());
                        partial.setLength(0);
                      }
                      ws.request(1);
                      return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int code, String why) {
                      closed = true;
                      return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable t) {
                      closed = true;
                    }
                  })
              .get(10, TimeUnit.SECONDS);
    }

    boolean send(String frame) {
      try {
        socket.sendText(frame, true).get(5, TimeUnit.SECONDS);
        return true;
      } catch (Exception e) {
        closed = true;
        return false;
      }
    }
  }

  private static String escaped(String s) {
    StringBuilder b = new StringBuilder();
    for (char c :
        s.length() > 400
            ? (s.substring(0, 400) + "...[" + s.length() + " chars]").toCharArray()
            : s.toCharArray()) {
      if (c == '\n') b.append("\\n");
      else if (c == '\r') b.append("\\r");
      else if (c == 0) b.append("\\0");
      else if (c < 32 || c > 126) b.append(String.format("\\u%04x", (int) c));
      else b.append(c);
    }
    return b.toString();
  }

  private static final String[] COMMANDS = {
    "CONNECT", "STOMP", "SEND", "SUBSCRIBE", "UNSUBSCRIBE", "ACK", "NACK", "BEGIN", "COMMIT",
    "ABORT", "DISCONNECT", "CONNECTED", "MESSAGE", "RECEIPT", "ERROR", "", "send", "SEND\r",
    "SEND ", "\u0000", "GET", "X".repeat(300)
  };

  private static final String[] HEADER_NAMES = {
    "destination",
    "id",
    "receipt",
    "content-type",
    "content-length",
    "heart-beat",
    "accept-version",
    "host",
    "login",
    "passcode",
    "ack",
    "transaction",
    "subscription",
    "message-id",
    "user-name",
    "selector",
    "x-custom",
    "",
    "destination",
    "destination"
  };

  private static String destination(Random r, UUID victimSos) {
    String sos = r.nextInt(3) == 0 ? victimSos.toString() : UUID.randomUUID().toString();
    String[] pool = {
      "/app/mechanic/location",
      "/app/mechanic/location/",
      "/app//mechanic/location",
      "/app/unknown",
      "/topic/sos/" + sos + "/location",
      "/topic/sos/" + sos + "/media",
      "/topic/sos/**",
      "/topic/**",
      "/topic/sos/*/location",
      "/topic//sos/" + sos + "/location",
      "/topic/sos/" + sos + "/location/extra",
      "/topic/sos/" + sos,
      "/topic/sos/%2e%2e/" + sos,
      "/topic/sos/" + sos + "/..",
      "/user/queue/errors",
      "/user/queue/sos-updates",
      "/user/" + victimSos + "/queue/sos-updates",
      "/queue/sos-updates",
      "/topic/sos/not-a-uuid/location",
      "/topic/sos/" + sos.toUpperCase() + "/location",
      "/topic/sos/" + sos + "/location\n",
      "",
      " ",
      "/",
      "//",
      "\u0000",
      "${jndi:ldap://x/a}",
      "../../etc/passwd",
      "'; DROP TABLE sos_request;--",
      "/app/" + "a".repeat(2_000),
      "/topic/" + "b/".repeat(500),
      "😀",
      "/topic/sos/" + sos + "/location#frag"
    };
    return pool[r.nextInt(pool.length)];
  }

  private static String headerValue(Random r, String name, UUID victimSos) {
    return switch (name) {
      case "destination" -> destination(r, victimSos);
      case "content-length" -> new String[] {"-1", "0", "9999999999", "abc", "5", ""}[r.nextInt(6)];
      case "heart-beat" ->
          new String[] {"0,0", "-1,-1", "abc", "99999999999,0", "1,1", ",", "10000,10000", "0"}
              [r.nextInt(8)];
      case "accept-version" -> new String[] {"1.2", "1.0,1.1", "9.9", "", "x"}[r.nextInt(5)];
      case "content-type" ->
          new String[] {
                "application/json",
                "text/plain",
                "application/octet-stream",
                "garbage",
                "",
                "application/json;charset=bogus"
              }
              [r.nextInt(6)];
      default ->
          r.nextInt(4) == 0 ? "v".repeat(1 + r.nextInt(3_000)) : Long.toHexString(r.nextLong());
    };
  }

  private static String body(Random r) {
    String[] pool = {
      "",
      "{}",
      "[]",
      "null",
      "\"\"",
      "0",
      "{\"latitude\":11.55,\"longitude\":104.92}",
      "{\"latitude\":1e999,\"longitude\":-1e999}",
      "{\"latitude\":\"x\",\"longitude\":null,\"sosId\":5}",
      "{\"latitude\":11.55,\"longitude\":104.92,\"sosId\":\"not-a-uuid\"}",
      "{\"latitude\":11.55,\"longitude\":104.92,\"mechanicId\":\"" + UUID.randomUUID() + "\"}",
      "{" + "\"a\":".repeat(500) + "1}",
      "[".repeat(2_000),
      "{\"latitude\":",
      "\u0001\u0002\u0003",
      "\\c\\n\\r",
      "x".repeat(20_000),
      "😀".repeat(100),
      "{\"latitude\":11.55,\"longitude\":104.92,\"sosId\":null}"
    };
    return pool[r.nextInt(pool.length)];
  }

  private static String frame(Random r, UUID victimSos) {
    StringBuilder f = new StringBuilder(COMMANDS[r.nextInt(COMMANDS.length)]).append('\n');
    int headers = r.nextInt(7);
    for (int i = 0; i < headers; i++) {
      String name = HEADER_NAMES[r.nextInt(HEADER_NAMES.length)];
      f.append(name).append(':').append(headerValue(r, name, victimSos)).append('\n');
    }
    f.append('\n').append(body(r));
    if (r.nextInt(10) != 0) f.append(NUL); // one in ten frames never terminates
    return f.toString();
  }

  @Test
  void randomFrames_neverHurtTheServer_neverLeakAnotherSosLocation_andHonestClientsStillWork()
      throws Exception {
    Driver victim = newDriver();
    Mechanic victimMechanic = newMechanic(11.55, 104.92);
    UUID victimSos = createSosOk(victim);
    StompSession victimSession =
        stomp.connect(port, "user-context", userContext(victim.user().getUserId()));
    Tap victimTap = subscribe(victimSession, "/topic/sos/" + victimSos + "/location");
    Thread.sleep(500);

    URI uri = URI.create("ws://localhost:" + port + "/ws");
    UUID attackerId = UUID.randomUUID();
    String attackerHeader = userContext(attackerId);
    Random random = new Random(SEED);
    List<Raw> sockets = new ArrayList<>();
    Raw raw = new Raw(uri, "user-context", attackerHeader);
    sockets.add(raw);
    raw.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n" + NUL);

    int sent = 0, reconnects = 0;
    String lastFrame = "";
    long worstHealth = 0;
    try {
      for (int i = 0; i < FRAMES; i++) {
        lastFrame = frame(random, victimSos);
        if (raw.closed || !raw.send(lastFrame)) {
          raw = new Raw(uri, "user-context", attackerHeader);
          sockets.add(raw);
          raw.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n" + NUL);
          reconnects++;
          continue;
        }
        sent++;
        if (i % 50 == 49) {
          long ms = healthProbeMillis(Duration.ofSeconds(3));
          assertThat(ms).as("/healthy after frame #%d", i).isGreaterThanOrEqualTo(0);
          worstHealth = Math.max(worstHealth, ms);
        }
      }

      // The victim's own mechanic pings; the victim must get it, and the attacker must not.
      StompSession mechanic =
          stomp.connect(port, "mechanic-context", mechanicContext(victimMechanic.getMechanicId()));
      sendPing(mechanic, ping(11.5611, 104.9311, victimSos));
      String delivered = victimTap.next(Duration.ofSeconds(10));
      assertThat(delivered)
          .as("honest subscriber still receives pings after the fuzzing")
          .isNotNull();
      mechanic.disconnect();
      Thread.sleep(500);

      for (Raw s : sockets) {
        for (String received : s.received) {
          assertThat(received.startsWith("MESSAGE") && received.contains(victimSos.toString()))
              .as("attacker socket received a frame for the victim's SOS: %s", escaped(received))
              .isFalse();
        }
      }

      // An honest client connects and works normally.
      StompSession honest = stomp.connect(port, "user-context", userContext(UUID.randomUUID()));
      assertThat(honest.isConnected()).isTrue();
      honest.disconnect();
    } catch (Throwable t) {
      System.out.println(
          "[fuzz-repro] seed="
              + SEED
              + " frames="
              + FRAMES
              + " sentSoFar="
              + sent
              + " lastFrame="
              + escaped(lastFrame)
              + " (replay: connect to /ws with a user-context header, send the frame, end with NUL)");
      throw t;
    } finally {
      for (Raw s : sockets) {
        if (!s.closed) s.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
      }
      victimSession.disconnect();
    }
    finding(
        "STOMP frame fuzz seed="
            + SEED
            + ": "
            + sent
            + " frames delivered, "
            + reconnects
            + " reconnects after the server closed the socket, worst /healthy "
            + worstHealth
            + " ms, no cross-SOS leak");

    long deadline = System.currentTimeMillis() + 30_000;
    while (users.getUser(attackerId.toString()) != null && System.currentTimeMillis() < deadline) {
      Thread.sleep(250);
    }
    assertThat(users.getUser(attackerId.toString()))
        .as("sessions the server still holds for the fuzzing client")
        .isNull();
  }
}
