package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import com.autofik.sos.service.impl.SosServiceImpl;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The mechanic's COMPLETED proposal against real Postgres: it waits for the ticket lock, so a
 * cancel that holds the lock first wins and no approvable row is written for the dead ticket.
 */
class ProposeCompletionLockIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";

  @Autowired private SosRequestRepository sosRequestRepository;
  @Autowired private SosStatusHistoryRepository historyRepository;
  @Autowired private SosServiceImpl sosService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private HttpResponse<String> updateStatus(Mechanic m, UUID sosId, SosStatus to) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/status?toStatus=" + to)
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  private void awaitABlockedLock() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      Integer waiting =
          jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(50);
    }
    throw new AssertionError("the completion proposal never waited for the ticket lock");
  }

  @Test
  void aCancelHoldingTheLock_winsOverACompletionProposalThatRunsAtTheSameTime() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    assignMechanic(d, m, sosId);
    assertThat(updateStatus(m, sosId, SosStatus.ON_THE_WAY).statusCode()).isEqualTo(200);
    arrive(d, m, sosId);
    assertThat(updateStatus(m, sosId, SosStatus.IN_PROGRESS).statusCode()).isEqualTo(200);

    // What a cancel does under the lock, held open while the proposal runs into it. Before the
    // fix the proposal read the ticket without the lock and wrote a PENDING_APPROVAL row for it.
    CompletableFuture<Object> proposal = new CompletableFuture<>();
    transactionTemplate.executeWithoutResult(
        status -> {
          sosRequestRepository.lockStatus(sosId);
          CompletableFuture.runAsync(
                  () ->
                      sosService.proposeStatusChange(
                          sosId,
                          new ProposeStatusChangeRequest(m.getMechanicId(), SosStatus.COMPLETED)))
              .whenComplete(
                  (ok, ex) -> {
                    if (ex == null) proposal.complete(ok);
                    else proposal.completeExceptionally(ex);
                  });
          try {
            awaitABlockedLock();
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          var sos = sosRequestRepository.findById(sosId).orElseThrow();
          sos.setStatus(SosStatus.CANCELLED);
          sosRequestRepository.save(sos);
        });

    assertThatThrownBy(() -> proposal.get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThat(sosStatus(d, sosId)).isEqualTo("CANCELLED");
    assertThat(historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId))
        .noneMatch(h -> h.getApprovedStatus() == ApprovedStatus.PENDING_APPROVAL);
  }
}
