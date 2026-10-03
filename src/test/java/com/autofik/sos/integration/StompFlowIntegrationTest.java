package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.domain.entity.Mechanic;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.user.SimpUserRegistry;

/**
 * STOMP over a real socket: who may connect, what a subscriber receives, and what happens to bad
 * frames. Location pings travel mechanic socket, Redis pub/sub, customer socket.
 */
class StompFlowIntegrationTest extends FlowTestSupport {

  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired private SimpUserRegistry users;

  @Test
  void connectWithoutIdentity_isRefusedAtTheUpgrade() {
    assertThatThrownBy(() -> stomp.connectWith(port, null, null, new NoOpHandler()))
        .isInstanceOf(Exception.class);
  }

  @Test
  void connectWithGarbageIdentity_isRefused() {
    assertThatThrownBy(
            () -> stomp.connectWith(port, "user-context", "{not json", new NoOpHandler()))
        .isInstanceOf(Exception.class);
  }

  @Test
  void ping_travelsThroughRedisPubSub_toTheCustomerSubscribedOnThatSos() throws Exception {
    Driver d = newDriver();
    Mechanic m = newMechanic(11.55, 104.92);
    UUID sosId = createSosOk(d);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    StompSession mechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap location = subscribe(customer, "/topic/sos/" + sosId + "/location");
    awaitSubscription("/topic/sos/" + sosId + "/location");

    sendPing(mechanic, ping(11.5601, 104.9302, sosId));

    String frame = location.next(WAIT);
    assertThat(frame).as("broadcast frame").isNotNull();
    assertThat(parse(frame).get("latitude").asDouble()).isEqualTo(11.5601);
    assertThat(parse(frame).get("longitude").asDouble()).isEqualTo(104.9302);
    assertThat(parse(frame).get("mechanicId").asString()).isEqualTo(m.getMechanicId().toString());
    assertThat(parse(frame).get("sosId").asString()).isEqualTo(sosId.toString());
    // The event is documented as Cambodian local time (+07:00); record what a client really gets.
    finding(
        "Redis pub/sub path: timestamp a client receives = "
            + parse(frame).get("timestamp").asString()
            + " (documented as +07:00 local time)");
    // Presence of the Postgres-write throttle in Redis proves the ping also took the Redis path.
    assertThat(redis.hasKey("mechanic:pg-sync:" + m.getMechanicId())).isTrue();
    customer.disconnect();
    mechanic.disconnect();
  }

  @Test
  void aSecondCustomer_whoDoesNotOwnTheSos_cannotSubscribeToItsLocation() throws Exception {
    Driver owner = newDriver();
    UUID sosId = createSosOk(owner);
    BlockingQueue<String> errors = new LinkedBlockingQueue<>();
    StompSession intruder =
        connectCapturingErrors("user-context", userContext(newDriver().user().getUserId()), errors);

    intruder.subscribe("/topic/sos/" + sosId + "/location", new NoOpHandler());

    String result = errors.poll(WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    assertThat(result).as("server reaction to a foreign subscription").isNotNull();
    long deadline = System.currentTimeMillis() + 5_000;
    while (intruder.isConnected() && System.currentTimeMillis() < deadline) Thread.sleep(50);
    assertThat(intruder.isConnected()).as("server closes the offending session").isFalse();
  }

  @Test
  void unsubscribe_stopsDelivery() throws Exception {
    Driver d = newDriver();
    Mechanic m = newMechanic(11.55, 104.92);
    UUID sosId = createSosOk(d);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    StompSession mechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    String dest = "/topic/sos/" + sosId + "/location";
    Tap tap = new Tap();
    StompSession.Subscription sub = customer.subscribe(dest, new TapHandler(tap));
    awaitSubscription(dest);

    sendPing(mechanic, ping(11.5601, 104.9302, sosId));
    assertThat(tap.next(WAIT)).as("delivery while subscribed").isNotNull();

    sub.unsubscribe();
    Thread.sleep(500);
    sendPing(mechanic, ping(11.5602, 104.9303, sosId));

    assertThat(tap.next(Duration.ofSeconds(3))).as("delivery after unsubscribe").isNull();
    customer.disconnect();
    mechanic.disconnect();
  }

  @Test
  void invalidPing_isAnswered_onTheCallersErrorQueue_andNeverBroadcast() throws Exception {
    Driver d = newDriver();
    Mechanic m = newMechanic(11.55, 104.92);
    UUID sosId = createSosOk(d);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    StompSession mechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap location = subscribe(customer, "/topic/sos/" + sosId + "/location");
    Tap errors = subscribe(mechanic, "/user/queue/errors");
    awaitSubscription("/topic/sos/" + sosId + "/location");

    sendPing(mechanic, ping(48.85, 2.35, sosId)); // Paris

    assertThat(errors.next(WAIT)).as("error frame on /user/queue/errors").isNotNull();
    assertThat(location.next(Duration.ofSeconds(2))).as("broadcast of a rejected ping").isNull();
    assertThat(mechanic.isConnected()).as("a bad frame must not close the socket").isTrue();
    customer.disconnect();
    mechanic.disconnect();
  }

  @Test
  void aCustomerCannotSendLocationPings() throws Exception {
    Driver d = newDriver();
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap errors = subscribe(customer, "/user/queue/errors");
    awaitUser(d.user().getUserId().toString());

    sendPing(customer, ping(11.55, 104.92, null));

    assertThat(errors.next(WAIT)).as("rejection frame").isNotNull();
    customer.disconnect();
  }

  @Test
  void disconnect_removesTheSessionFromThePresenceRegistry() throws Exception {
    UUID id = UUID.randomUUID();
    StompSession s = stomp.connect(port, "user-context", userContext(id));
    awaitUser(id.toString());
    assertThat(users.getUser(id.toString())).isNotNull();

    s.disconnect();

    long deadline = System.currentTimeMillis() + 10_000;
    while (users.getUser(id.toString()) != null && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    assertThat(users.getUser(id.toString())).as("presence after disconnect").isNull();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** SUBSCRIBE is asynchronous; wait until the broker has registered it before sending. */
  private void awaitSubscription(String destination) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      boolean found =
          users.getUsers().stream()
              .flatMap(u -> u.getSessions().stream())
              .flatMap(s -> s.getSubscriptions().stream())
              .anyMatch(sub -> destination.equals(sub.getDestination()));
      if (found) return;
      Thread.sleep(50);
    }
    throw new AssertionError("subscription never registered: " + destination);
  }

  private void awaitUser(String name) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (users.getUser(name) == null && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    Thread.sleep(200); // let the SUBSCRIBE that follows the CONNECT land
  }

  private static class NoOpHandler
      extends org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter {}

  private static final class TapHandler
      implements org.springframework.messaging.simp.stomp.StompFrameHandler {
    private final Tap tap;

    TapHandler(Tap tap) {
      this.tap = tap;
    }

    @Override
    public java.lang.reflect.Type getPayloadType(
        org.springframework.messaging.simp.stomp.StompHeaders h) {
      return byte[].class;
    }

    @Override
    public void handleFrame(
        org.springframework.messaging.simp.stomp.StompHeaders h, Object payload) {
      tap.frames.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
