package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * A mechanic holds pending bids on several tickets but may be accepted on one live job only. The
 * accept takes the mechanic's row lock after the ticket lock, so two accepts for the same mechanic
 * on different tickets queue behind each other and the second sees the first's job.
 */
class MechanicSingleJobIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";

  @Autowired private SosStatusHistoryRepository historyRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  /** One mechanic's pending bid on a ticket of one driver. */
  private record Offer(Driver driver, UUID sosId, UUID bidId) {}

  private Offer bidOnNewTicket(Mechanic m) throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);
    return new Offer(d, sosId, placeBidOk(m, sosId));
  }

  private int accept(Offer o) throws Exception {
    return acceptBid(o.driver(), o.bidId()).statusCode();
  }

  private String bidStatus(Offer o) throws Exception {
    JsonNode bids =
        parse(
            send(req("/api/v1/bids/sos/" + o.sosId())
                    .header("user-context", userContext(o.driver().user().getUserId()))
                    .GET())
                .body());
    for (JsonNode b : bids) {
      if (o.bidId().toString().equals(b.get("bidId").asString())) return b.get("status").asString();
    }
    throw new AssertionError("bid " + o.bidId() + " not listed on " + o.sosId());
  }

  private HttpResponse<String> updateStatus(Mechanic m, UUID sosId, SosStatus to) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/status?toStatus=" + to)
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  private HttpResponse<String> release(Mechanic m, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/mechanic-release")
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  private HttpResponse<String> cancelTicket(Driver d, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/cancel")
            .header("user-context", userContext(d.user().getUserId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  private void complete(Driver d, Mechanic m, UUID sosId) throws Exception {
    HttpResponse<String> proposed =
        send(
            req("/api/v1/sos/" + sosId + "/completion")
                .header("mechanic-context", mechanicContext(m.getMechanicId()))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"mechanicId\":\""
                            + m.getMechanicId()
                            + "\",\"toStatus\":\"COMPLETED\"}")));
    assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
    UUID historyId = UUID.fromString(parse(proposed.body()).get("id").asString());
    HttpResponse<String> approved = answerRequest(d, historyId, ApprovedStatus.ACCEPTED.name());
    assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
  }

  /** Waits until this many other sessions are blocked on a lock; fails if they never are. */
  private void awaitBlockedSessions(int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity"
                  + " WHERE wait_event_type = 'Lock' AND datname = current_database()",
              Integer.class);
      if (waiting != null && waiting >= count) return;
      Thread.sleep(25);
    }
    throw new AssertionError(count + " accepts never waited for the mechanic lock");
  }

  // ── sequential ─────────────────────────────────────────────────────────────

  @Test
  void aSecondAcceptOfTheSameMechanic_is409_andLeavesTheSecondTicketAndBidPending()
      throws Exception {
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Offer a = bidOnNewTicket(m);
    Offer b = bidOnNewTicket(m);
    StompSession mechanicSession =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap frames = subscribe(mechanicSession, "/user/queue/sos-updates");
    awaitSubscriptions("/user/queue/sos-updates", 1);

    assertThat(accept(a)).isEqualTo(200);
    assertThat(frames.next(Duration.ofSeconds(10)))
        .contains("BID_ACCEPTED")
        .contains(a.sosId().toString());

    HttpResponse<String> second = acceptBid(b.driver(), b.bidId());

    assertThat(second.statusCode()).as(second.body()).isEqualTo(409);
    assertThat(sosStatus(a.driver(), a.sosId())).isEqualTo("ACCEPTED");
    assertThat(sosStatus(b.driver(), b.sosId())).isEqualTo("PENDING");
    assertThat(bidStatus(b)).isEqualTo("PENDING");
    assertThat(historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(b.sosId())).isEmpty();
    String stray;
    while ((stray = frames.next(Duration.ofSeconds(1))) != null) {
      assertThat(stray).as("a frame for the refused ticket").doesNotContain(b.sosId().toString());
    }
    mechanicSession.disconnect();
  }

  // ── concurrent ─────────────────────────────────────────────────────────────

  /**
   * Both accepts are held at the mechanic's row lock, with their ticket locks taken and their
   * status checks passed, before either may continue. That is the race itself, not a hope of
   * hitting it: without the lock they would not wait here and the await fails.
   */
  @Test
  void twoAcceptsOfTheSameMechanic_waitOnTheMechanicLock_andExactlyOneWins() throws Exception {
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Offer a = bidOnNewTicket(m);
    Offer b = bidOnNewTicket(m);

    List<CompletableFuture<Integer>> results = new ArrayList<>();
    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            mechanicRepository.lockById(m.getMechanicId());
            for (Offer o : List.of(a, b)) {
              results.add(
                  CompletableFuture.supplyAsync(
                      () -> {
                        try {
                          return accept(o);
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      }));
            }
            try {
              awaitBlockedSessions(2);
            } catch (InterruptedException e) {
              throw new IllegalStateException(e);
            }
          });
    } finally {
      // Whatever happened above, do not leave the requests running past the test.
      results.forEach(r -> r.orTimeout(30, TimeUnit.SECONDS));
    }

    List<Integer> codes = new ArrayList<>();
    for (CompletableFuture<Integer> r : results) codes.add(r.get(30, TimeUnit.SECONDS));
    assertThat(codes).containsExactlyInAnyOrder(200, 409);

    Offer winner = codes.get(0) == 200 ? a : b;
    Offer loser = winner == a ? b : a;
    assertThat(sosStatus(winner.driver(), winner.sosId())).isEqualTo("ACCEPTED");
    assertThat(sosStatus(loser.driver(), loser.sosId())).isEqualTo("PENDING");
    assertThat(bidStatus(loser)).isEqualTo("PENDING");
  }

  /** Same race without holding anything back, over several rounds. */
  @Test
  void twoConcurrentAcceptsOfTheSameMechanic_overManyRounds_neverBothWin() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 5; round++) {
        Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
        Offer a = bidOnNewTicket(m);
        Offer b = bidOnNewTicket(m);

        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> ra =
            pool.submit(
                () -> {
                  go.await();
                  return accept(a);
                });
        Future<Integer> rb =
            pool.submit(
                () -> {
                  go.await();
                  return accept(b);
                });
        go.countDown();

        assertThat(List.of(ra.get(30, TimeUnit.SECONDS), rb.get(30, TimeUnit.SECONDS)))
            .as("round %d", round)
            .containsExactlyInAnyOrder(200, 409);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentAcceptsOfDifferentMechanics_bothSucceed() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Offer a = bidOnNewTicket(skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE));
      Offer b = bidOnNewTicket(skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE));

      CountDownLatch go = new CountDownLatch(1);
      Future<Integer> ra =
          pool.submit(
              () -> {
                go.await();
                return accept(a);
              });
      Future<Integer> rb =
          pool.submit(
              () -> {
                go.await();
                return accept(b);
              });
      go.countDown();

      assertThat(ra.get(30, TimeUnit.SECONDS)).isEqualTo(200);
      assertThat(rb.get(30, TimeUnit.SECONDS)).isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }
  }

  // ── when the first job stops counting ──────────────────────────────────────

  @Test
  void whileTheFirstJobIsLive_theSecondAcceptIs409_andOnceCompleted_itIs200() throws Exception {
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Offer a = bidOnNewTicket(m);
    Offer b = bidOnNewTicket(m);
    assertThat(accept(a)).isEqualTo(200);
    assertThat(accept(b)).as("ACCEPTED").isEqualTo(409);

    assertThat(updateStatus(m, a.sosId(), SosStatus.ON_THE_WAY).statusCode()).isEqualTo(200);
    assertThat(accept(b)).as("ON_THE_WAY").isEqualTo(409);

    arrive(a.driver(), m, a.sosId());
    assertThat(accept(b)).as("ARRIVED").isEqualTo(409);

    assertThat(updateStatus(m, a.sosId(), SosStatus.IN_PROGRESS).statusCode()).isEqualTo(200);
    assertThat(accept(b)).as("IN_PROGRESS").isEqualTo(409);
    assertThat(sosStatus(b.driver(), b.sosId())).isEqualTo("PENDING");

    complete(a.driver(), m, a.sosId());
    assertThat(sosStatus(a.driver(), a.sosId())).isEqualTo("COMPLETED");

    assertThat(accept(b)).as("after COMPLETED").isEqualTo(200);
    assertThat(sosStatus(b.driver(), b.sosId())).isEqualTo("ACCEPTED");
  }

  @Test
  void afterTheMechanicReleasesTheFirstJob_theSecondAcceptIs200() throws Exception {
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Offer a = bidOnNewTicket(m);
    Offer b = bidOnNewTicket(m);
    assertThat(accept(a)).isEqualTo(200);

    assertThat(release(m, a.sosId()).statusCode()).isEqualTo(200);

    assertThat(accept(b)).isEqualTo(200);
    assertThat(sosStatus(b.driver(), b.sosId())).isEqualTo("ACCEPTED");
  }

  @Test
  void afterTheDriverCancelsTheFirstJob_theSecondAcceptIs200() throws Exception {
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Offer a = bidOnNewTicket(m);
    Offer b = bidOnNewTicket(m);
    assertThat(accept(a)).isEqualTo(200);

    // The cancelled ticket's bid stays ACCEPTED; only the ticket status frees the mechanic.
    assertThat(cancelTicket(a.driver(), a.sosId()).statusCode()).isEqualTo(200);

    assertThat(accept(b)).isEqualTo(200);
    assertThat(sosStatus(b.driver(), b.sosId())).isEqualTo("ACCEPTED");
  }

  // ── assumption the lock relies on ──────────────────────────────────────────

  /** The active-job read after the lock must see the winner's commit, which needs this level. */
  @Test
  void transactionsRunAtReadCommitted() {
    assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
        .isEqualTo("read committed");
  }
}
