package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis and Kafka going away, going slow, and coming back, using {@code docker pause} so the
 * containers keep their state and the sockets stay open but unanswered (the nasty case: not a clean
 * connection refusal).
 *
 * <p>These tests assert only what must hold in any healthy design: the process stays up and keeps
 * answering, requests finish in bounded time, and everything works again after recovery. What the
 * service actually does meanwhile (status code, how long a caller waits) is recorded as a {@code
 * [finding]} line in the output and ends up in the integration report.
 */
class DependencyFailureIntegrationTest extends FlowTestSupport {

  private void pause(GenericContainer<?> c) {
    c.getDockerClient().pauseContainerCmd(c.getContainerId()).exec();
  }

  private void unpause(GenericContainer<?> c) {
    try {
      c.getDockerClient().unpauseContainerCmd(c.getContainerId()).exec();
    } catch (RuntimeException alreadyRunning) {
      // not paused: nothing to undo
    }
  }

  @Test
  void redisPaused_pingStillReachesTheCustomer_throughTheKafkaFallback() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(11.55, 104.92, "TOW");
    UUID sosId = createSosOk(d);
    assignMechanic(d, m, sosId); // only the assigned mechanic's pings are broadcast
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    StompSession mechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap location = subscribe(customer, "/topic/sos/" + sosId + "/location");
    Thread.sleep(500);

    pause(REDIS);
    long start = System.nanoTime();
    try {
      sendPing(mechanic, ping(11.5701, 104.9401, sosId));
      String frame = location.next(Duration.ofSeconds(60));
      long ms = (System.nanoTime() - start) / 1_000_000;
      finding("Redis paused: ping-to-broadcast via Kafka fallback took " + ms + " ms");
      assertThat(frame).as("broadcast while Redis is unreachable").isNotNull();
      assertThat(parse(frame).get("latitude").asDouble()).isEqualTo(11.5701);
      assertThat(healthProbeMillis(Duration.ofSeconds(5)))
          .as("/healthy while Redis is down")
          .isGreaterThanOrEqualTo(0);
    } finally {
      unpause(REDIS);
    }

    // Recovery: the next ping goes back through Redis without a restart.
    sendPing(mechanic, ping(11.5702, 104.9402, sosId));
    String after = null;
    long deadline = System.currentTimeMillis() + 60_000;
    while (after == null && System.currentTimeMillis() < deadline) {
      sendPing(mechanic, ping(11.5702, 104.9402, sosId));
      after = location.next(Duration.ofSeconds(5));
    }
    assertThat(after).as("broadcast after Redis recovered").isNotNull();
    customer.disconnect();
    mechanic.disconnect();
  }

  @Test
  void redisStallShorterThanTheCommandTimeout_isAbsorbed_withoutALostPing() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(11.55, 104.92, "TOW");
    UUID sosId = createSosOk(d);
    assignMechanic(d, m, sosId); // only the assigned mechanic's pings are broadcast
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    StompSession mechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap location = subscribe(customer, "/topic/sos/" + sosId + "/location");
    Thread.sleep(500);

    pause(REDIS);
    long start = System.nanoTime();
    sendPing(mechanic, ping(11.5703, 104.9403, sosId));
    Thread.sleep(500); // well under REDIS_TIMEOUT_MS (1000)
    unpause(REDIS);

    String frame = location.next(Duration.ofSeconds(30));
    long ms = (System.nanoTime() - start) / 1_000_000;
    finding("Redis stalled 0.5 s: ping delivered after " + ms + " ms");
    assertThat(frame).as("ping sent during a short Redis stall").isNotNull();
    customer.disconnect();
    mechanic.disconnect();
  }

  @Test
  void redisPaused_createSos_isBoundedInTime_andRecoversAfterwards() throws Exception {
    Driver d = newDriver();
    pause(REDIS);
    HttpResponse<String> during;
    long start = System.nanoTime();
    try {
      during = createSos(d);
    } finally {
      unpause(REDIS);
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    finding(
        "Redis paused: POST /api/v1/sos answered "
            + during.statusCode()
            + " after "
            + ms
            + " ms (the double-tap lock fails open when Redis is unreachable)");
    assertThat(ms).as("create while Redis is down").isLessThan(15_000);
    assertThat(during.statusCode())
        .as("an SOS must still be creatable: %s", during.body())
        .isEqualTo(201);

    // Recovery: a different driver can create normally once Redis is back.
    Driver other = newDriver();
    long deadline = System.currentTimeMillis() + 60_000;
    int status = -1;
    while (status != 201 && System.currentTimeMillis() < deadline) {
      status = createSos(other).statusCode();
      if (status != 201) Thread.sleep(1_000);
    }
    assertThat(status).as("create after Redis recovered").isEqualTo(201);
  }

  @Test
  void kafkaPaused_createSos_isBounded_theAppStaysUp_andProducingResumes() throws Exception {
    Driver d = newDriver();
    pause(KAFKA);
    HttpResponse<String> during;
    long start = System.nanoTime();
    long healthMs;
    try {
      java.util.concurrent.CompletableFuture<HttpResponse<String>> create =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return createSos(d);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      Thread.sleep(1_000);
      healthMs = healthProbeMillis(Duration.ofSeconds(5));
      during = create.get(120, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      unpause(KAFKA);
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    finding(
        "Kafka paused: POST /api/v1/sos answered "
            + during.statusCode()
            + " after "
            + ms
            + " ms; /healthy answered in "
            + healthMs
            + " ms while the create was in flight");
    assertThat(healthMs).as("/healthy while a create waits on Kafka").isGreaterThanOrEqualTo(0);
    assertThat(ms).as("create while Kafka is down (producer max.block.ms)").isLessThan(15_000);
    assertThat(during.statusCode()).as(during.body()).isEqualTo(201);

    // Recovery: after unpause a fresh driver's create is fast and lands on the topic.
    Driver other = newDriver();
    long t0 = System.nanoTime();
    long deadline = System.currentTimeMillis() + 90_000;
    int status = -1;
    while (status != 201 && System.currentTimeMillis() < deadline) {
      status = createSos(other).statusCode();
    }
    assertThat(status).isEqualTo(201);
    finding(
        "Kafka resumed: next create answered 201 after "
            + (System.nanoTime() - t0) / 1_000_000
            + " ms");
  }
}
