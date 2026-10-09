package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.impl.SosServiceImpl;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expiry against real Postgres: it waits for the ticket lock, so an accept that holds the lock
 * first wins, and the sweep expires a ticket whose timer never fired.
 */
class ExpiryLockIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";

  @Autowired private BidSosRepository bidSosRepository;
  @Autowired private SosRequestRepository sosRequestRepository;
  @Autowired private SosServiceImpl sosService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private void windowRanOut(UUID sosId) {
    var sos = sosRequestRepository.findById(sosId).orElseThrow();
    sos.setBiddingExpiresAt(LocalDateTime.now().minusMinutes(1));
    sosRequestRepository.save(sos);
  }

  private void awaitABlockedLock() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      Integer waiting =
          jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(50);
    }
    throw new AssertionError("the expiry never waited for the ticket lock");
  }

  @Test
  void anAcceptHoldingTheLock_winsOverAnExpiryThatRunsAtTheSameTime() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID bidId = placeBidOk(m, sosId);
    windowRanOut(sosId);

    // What an accept does under the lock, held open while the expiry runs into it. Before the fix
    // the expiry read the ticket without the lock and wrote EXPIRED over the committed ACCEPTED.
    CompletableFuture<Void> expiry = new CompletableFuture<>();
    transactionTemplate.executeWithoutResult(
        status -> {
          sosRequestRepository.lockStatus(sosId);
          CompletableFuture.runAsync(sosService::expireOverduePendingRequests)
              .whenComplete((ok, ex) -> complete(expiry, ex));
          try {
            awaitABlockedLock();
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          var sos = sosRequestRepository.findById(sosId).orElseThrow();
          sos.setStatus(SosStatus.ACCEPTED);
          sosRequestRepository.save(sos);
          var bid = bidSosRepository.findById(bidId).orElseThrow();
          bid.setStatus(BidStatus.ACCEPTED);
          bidSosRepository.save(bid);
        });
    expiry.get(10, TimeUnit.SECONDS);

    assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");
    assertThat(bidSosRepository.findById(bidId).orElseThrow().getStatus())
        .isEqualTo(BidStatus.ACCEPTED);
  }

  @Test
  void theSweep_expiresATicketWhoseTimerNeverFired_andClosesItsBids() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID bidId = placeBidOk(m, sosId);
    windowRanOut(sosId); // the timer set at create is still minutes away

    sosService.sweepOverdueExpiry();

    assertThat(sosStatus(d, sosId)).isEqualTo("EXPIRED");
    assertThat(bidSosRepository.findById(bidId).orElseThrow().getStatus())
        .isEqualTo(BidStatus.REJECTED);
  }

  private static void complete(CompletableFuture<Void> f, Throwable ex) {
    if (ex == null) f.complete(null);
    else f.completeExceptionally(ex);
  }
}
