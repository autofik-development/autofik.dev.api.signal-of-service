package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Connection churn and a location-ping flood against the running app. The question is whether
 * clients behaving badly or just in bulk can degrade the service for everyone else, so the
 * assertions are about the rest of the app staying responsive, and about the server cleaning up
 * after the clients are gone.
 *
 * <p>Sizes are deliberately modest so a laptop can run them; raise them for a real soak with {@code
 * -Dit.ws.connections=2000 -Dit.ws.pingers=200 -Dit.ws.pings=500}.
 */
class WebSocketStormIntegrationTest extends IntegrationTestBase {

  private static final int CONNECTIONS = Integer.getInteger("it.ws.connections", 150);
  private static final int PINGERS = Integer.getInteger("it.ws.pingers", 25);
  private static final int PINGS_EACH = Integer.getInteger("it.ws.pings", 60);

  // Slowest health answer we accept while the storm is running. Generous: the point is "still
  // answering", not a latency target.
  private static final Duration HEALTH_LIMIT = Duration.ofSeconds(3);

  @Autowired private SimpUserRegistry userRegistry;

  // The channel the STOMP frames from clients are processed on; its executor is the pool under
  // test.
  @Autowired private org.springframework.context.ApplicationContext context;

  @Autowired
  @Qualifier("clientInboundChannel")
  private ExecutorSubscribableChannel inboundChannel;

  private final StompTestClient stomp = new StompTestClient();

  /** Subscribes without caring what arrives; the storm is about the connections, not the data. */
  private static final StompFrameHandler IGNORE =
      new StompFrameHandler() {
        @Override
        public Type getPayloadType(StompHeaders headers) {
          return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {}
      };

  /** Samples /healthy every 200 ms until stopped, remembering the worst answer. */
  private final class HealthWatcher implements AutoCloseable {
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong worstMillis = new AtomicLong();
    private final AtomicInteger failures = new AtomicInteger();
    private final Thread thread =
        Thread.ofVirtual()
            .unstarted(
                () -> {
                  while (running.get()) {
                    long ms = healthProbeMillis(HEALTH_LIMIT);
                    if (ms < 0) failures.incrementAndGet();
                    else worstMillis.accumulateAndGet(ms, Math::max);
                    try {
                      Thread.sleep(200);
                    } catch (InterruptedException e) {
                      return;
                    }
                  }
                });

    HealthWatcher() {
      thread.start();
    }

    @Override
    public void close() throws InterruptedException {
      running.set(false);
      thread.join(5_000);
    }
  }

  /** The pool behind the inbound channel, whichever Spring wrapper it sits in. */
  private static ThreadPoolExecutor poolOf(java.util.concurrent.Executor executor) {
    if (executor instanceof ThreadPoolTaskExecutor t) return t.getThreadPoolExecutor();
    if (executor instanceof ThreadPoolTaskScheduler t) return t.getScheduledThreadPoolExecutor();
    throw new IllegalStateException("Unexpected inbound executor: " + executor.getClass());
  }

  @Test
  void connectionChurn_leavesTheAppResponsive_andNoSessionsBehind() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(20);
    AtomicInteger failed = new AtomicInteger();
    HealthWatcher watcher = new HealthWatcher();
    try {
      List<Future<?>> all = new ArrayList<>();
      for (int i = 0; i < CONNECTIONS; i++) {
        boolean asMechanic = i % 2 == 0;
        all.add(
            pool.submit(
                () -> {
                  try {
                    UUID id = UUID.randomUUID();
                    StompSession session =
                        asMechanic
                            ? stomp.connect(port, "mechanic-context", mechanicContext(id))
                            : stomp.connect(port, "user-context", userContext(id));
                    session.subscribe("/user/queue/sos-updates", IGNORE);
                    session.disconnect();
                  } catch (Exception e) {
                    failed.incrementAndGet();
                  }
                }));
      }
      for (Future<?> f : all) f.get(120, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
      watcher.close();
    }

    assertThat(failed.get()).as("connections that failed to connect/subscribe/disconnect").isZero();
    assertThat(watcher.failures.get()).as("health probes that failed during the storm").isZero();
    System.out.println(
        "STORM connections=" + CONNECTIONS + " worstHealthMs=" + watcher.worstMillis.get());

    // Every client is gone, so the server must have forgotten them all.
    long deadline = System.currentTimeMillis() + 30_000;
    while (userRegistry.getUserCount() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(250);
    }
    assertThat(userRegistry.getUserCount())
        .as("sessions the server still holds after every client disconnected")
        .isZero();
  }

  @Test
  void locationPingFlood_doesNotStarveTheRestOfTheApp_andTheQueueDrains() throws Exception {
    List<Mechanic> mechanics = new ArrayList<>();
    for (int i = 0; i < PINGERS; i++) mechanics.add(newMechanic(11.55, 104.92));

    ExecutorService pool = Executors.newFixedThreadPool(PINGERS);
    ThreadPoolExecutor executor = poolOf(inboundChannel.getExecutor());
    // Regression guard: the channel once ran on the one-thread heartbeat scheduler, so a single
    // slow frame stalled every other client. It must be a real pool.
    assertThat(executor.getCorePoolSize())
        .as("inbound channel pool size (largest ever=%d)", executor.getLargestPoolSize())
        .isGreaterThan(1);
    // Which bean is actually serving as the inbound executor? The config asks for 32-64 threads.
    context
        .getBeansOfType(java.util.concurrent.Executor.class)
        .forEach(
            (name, bean) ->
                System.out.println(
                    "EXECUTOR bean="
                        + name
                        + " type="
                        + bean.getClass().getSimpleName()
                        + (bean == inboundChannel.getExecutor()
                            ? "  <== inbound channel uses this"
                            : "")));
    AtomicInteger maxQueue = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    HealthWatcher watcher = new HealthWatcher();
    try {
      List<Future<?>> all = new ArrayList<>();
      for (Mechanic mechanic : mechanics) {
        all.add(
            pool.submit(
                () -> {
                  try {
                    StompSession session =
                        stomp.connect(
                            port, "mechanic-context", mechanicContext(mechanic.getMechanicId()));
                    for (int n = 0; n < PINGS_EACH; n++) {
                      // A little jitter inside Phnom Penh, as a moving mechanic would send.
                      double lat = 11.55 + (n % 10) * 0.0005;
                      double lng = 104.92 + (n % 10) * 0.0005;
                      session.send(
                          "/app/mechanic/location",
                          "{\"latitude\":" + lat + ",\"longitude\":" + lng + "}");
                      maxQueue.accumulateAndGet(executor.getQueue().size(), Math::max);
                    }
                    session.disconnect();
                  } catch (Exception e) {
                    failed.incrementAndGet();
                  }
                }));
      }
      for (Future<?> f : all) f.get(180, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
      watcher.close();
    }

    System.out.println(
        "PINGFLOOD pingers="
            + PINGERS
            + " pingsEach="
            + PINGS_EACH
            + " maxInboundQueue="
            + maxQueue.get()
            + " executorType="
            + inboundChannel.getExecutor().getClass().getSimpleName()
            + " queueType="
            + executor.getQueue().getClass().getSimpleName()
            + " largestPool="
            + executor.getLargestPoolSize()
            + " corePool="
            + executor.getCorePoolSize()
            + " maxPool="
            + executor.getMaximumPoolSize()
            + " worstHealthMs="
            + watcher.worstMillis.get());

    assertThat(failed.get()).as("pingers that failed").isZero();
    assertThat(watcher.failures.get()).as("health probes that failed during the flood").isZero();

    // Everything sent has to be processed, not left sitting in the queue.
    long deadline = System.currentTimeMillis() + 60_000;
    while (executor.getQueue().size() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(250);
    }
    assertThat(executor.getQueue().size())
        .as("location pings still queued a minute after the flood stopped")
        .isZero();
  }
}
