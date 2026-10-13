package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.impl.SosServiceImpl;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The customer's retry of an EXPIRED ticket. It takes the ticket lock, so a double tap reopens it
 * once (one timer, one fan-out, one count), and it refuses while the customer has another live
 * ticket.
 */
class RebroadcastLockIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";

  @Autowired private SosRequestRepository sosRequestRepository;
  @Autowired private SosServiceImpl sosService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  /** Counts the expiry timers a reopen schedules. */
  @MockitoSpyBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  /** An EXPIRED ticket: it had a bid (so it expires instead of widening) and its window ran out. */
  private UUID expiredTicket(Driver d) throws Exception {
    Mechanic bidder = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    placeBidOk(bidder, sosId);
    var sos = sosRequestRepository.findById(sosId).orElseThrow();
    sos.setBiddingExpiresAt(LocalDateTime.now().minusMinutes(1));
    sosRequestRepository.save(sos);
    sosService.expireOverduePendingRequests();
    assertThat(sosStatus(d, sosId)).isEqualTo("EXPIRED");
    return sosId;
  }

  /** A second ticket for the same customer: the create cooldown is what keeps tests from it. */
  private UUID secondTicket(Driver d) throws Exception {
    redis.delete("sos:cooldown:" + d.user().getUserId());
    return createSosOk(d);
  }

  private HttpResponse<String> rebroadcast(Driver d, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/rebroadcast")
            .header("user-context", userContext(d.user().getUserId()))
            .POST(HttpRequest.BodyPublishers.noBody()));
  }

  private HttpResponse<String> cancelTicket(Driver d, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/cancel")
            .header("user-context", userContext(d.user().getUserId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  private int rebroadcastCount(UUID sosId) {
    return sosRequestRepository.findById(sosId).orElseThrow().getRebroadcastCount();
  }

  /** Frames of this type about this ticket that reach the tap within the quiet period. */
  private int framesOfType(Tap tap, String type, UUID sosId) throws Exception {
    int seen = 0;
    String frame;
    while ((frame = tap.next(java.time.Duration.ofSeconds(1))) != null) {
      JsonNode node = parse(frame);
      if (node.has("type")
          && type.equals(node.get("type").asString())
          && sosId.toString().equals(node.get("sosId").asString())) {
        seen++;
      }
    }
    return seen;
  }

  /** True once this many other sessions are blocked on a lock; false if they never are. */
  private boolean awaitBlockedSessions(int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      // pg_stat_activity is a snapshot kept for the whole transaction, and this runs inside the
      // one that holds the lock: without this, a poll before the second tap arrives is the last.
      jdbc.execute("SELECT pg_stat_clear_snapshot()");
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity"
                  + " WHERE wait_event_type = 'Lock' AND datname = current_database()",
              Integer.class);
      if (waiting != null && waiting >= count) return true;
      Thread.sleep(25);
    }
    lastSnapshot =
        jdbc.queryForList(
                "SELECT pid, state, wait_event_type, wait_event, left(query, 90) AS query"
                    + " FROM pg_stat_activity WHERE datname = current_database()"
                    + " AND pid <> pg_backend_pid() AND state <> 'idle'")
            .toString();
    return false;
  }

  /**
   * What Postgres was running when the taps were not both seen waiting; for the failure message.
   */
  private String lastSnapshot = "(not taken)";

  @Test
  void rebroadcast_whileAnotherTicketIsLive_is409_andLeavesItExpired() throws Exception {
    Driver d = newDriver();
    UUID expired = expiredTicket(d);
    UUID live = secondTicket(d);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap frames = subscribe(customer, "/user/queue/sos-updates");
    awaitSubscriptions("/user/queue/sos-updates", 1);

    HttpResponse<String> r = rebroadcast(d, expired);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
    assertThat(parse(r.body()).get("message").asString()).contains("already have an active SOS");
    assertThat(sosStatus(d, expired)).isEqualTo("EXPIRED");
    assertThat(sosStatus(d, live)).isEqualTo("PENDING");
    assertThat(rebroadcastCount(expired)).isZero();
    assertThat(framesOfType(frames, "SOS_REBROADCAST", expired)).isZero();
    customer.disconnect();
  }

  @Test
  void rebroadcast_afterTheOtherTicketEnds_is200() throws Exception {
    Driver d = newDriver();
    UUID expired = expiredTicket(d);
    UUID live = secondTicket(d);
    assertThat(rebroadcast(d, expired).statusCode()).isEqualTo(409);

    assertThat(cancelTicket(d, live).statusCode()).isEqualTo(200);
    HttpResponse<String> r = rebroadcast(d, expired);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(sosStatus(d, expired)).isEqualTo("PENDING");
    assertThat(rebroadcastCount(expired)).isEqualTo(1);
  }

  @Test
  void rebroadcast_otherUsersLiveTicket_doesNotBlock() throws Exception {
    Driver d = newDriver();
    UUID expired = expiredTicket(d);
    Driver other = newDriver();
    createSosOk(other);

    HttpResponse<String> r = rebroadcast(d, expired);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(sosStatus(d, expired)).isEqualTo("PENDING");
  }

  /**
   * Both taps are held at the ticket lock until it is released, so they truly overlap. They then
   * run one after the other: the first reopens the ticket, the second finds it PENDING.
   */
  @Test
  void doubleTapRebroadcast_onlyOneReopens() throws Exception {
    Driver d = newDriver();
    UUID expired = expiredTicket(d);
    int countBefore = rebroadcastCount(expired);
    // A mechanic who was not around at the first broadcast: the reopen fan-out reaches them.
    Mechanic watcher = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    StompSession watching =
        stomp.connect(port, "mechanic-context", mechanicContext(watcher.getMechanicId()));
    Tap customerFrames = subscribe(customer, "/user/queue/sos-updates");
    Tap offers = subscribe(watching, "/user/queue/sos-new-request");
    awaitSubscriptions("/user/queue/sos-updates", 1);
    awaitSubscriptions("/user/queue/sos-new-request", 1);
    clearInvocations(taskScheduler, googleMapsService);

    // Straight into the service from two threads: nothing between the test and the lock (no HTTP
    // client) can delay one of the taps, so both really are waiting when the lock is released.
    // Their own threads: the shared common pool may have one free worker, and the first tap would
    // then hold it while waiting, so the second could never even start.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<CompletableFuture<String>> taps = new ArrayList<>();
    boolean[] bothWaited = new boolean[1];
    transactionTemplate.executeWithoutResult(
        status -> {
          sosRequestRepository.lockStatus(expired); // what a reopen takes first
          for (int i = 0; i < 2; i++) {
            taps.add(
                CompletableFuture.supplyAsync(
                        () -> sosService.rebroadcast(expired, d.user().getUserId()), pool)
                    .handle(
                        (ok, ex) ->
                            ex == null
                                ? "OK"
                                : ex.getCause() instanceof IllegalStateException
                                    ? "CONFLICT"
                                    : String.valueOf(ex.getCause())));
          }
          try {
            bothWaited[0] = awaitBlockedSessions(2);
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
        });
    List<String> outcomes = new ArrayList<>();
    try {
      for (CompletableFuture<String> t : taps) outcomes.add(t.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    assertThat(outcomes).containsExactlyInAnyOrder("OK", "CONFLICT");
    assertThat(sosStatus(d, expired)).isEqualTo("PENDING");
    assertThat(rebroadcastCount(expired)).isEqualTo(countBefore + 1);
    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    verify(googleMapsService, times(1)).getDistanceInfos(any(), any());
    assertThat(framesOfType(offers, "SOS_CREATED", expired)).isEqualTo(1);
    assertThat(framesOfType(customerFrames, "SOS_REBROADCAST", expired)).isEqualTo(1);
    assertThat(bothWaited[0])
        .as("both taps waited for the ticket lock; active sessions: %s", lastSnapshot)
        .isTrue();
    customer.disconnect();
    watching.disconnect();
  }
}
