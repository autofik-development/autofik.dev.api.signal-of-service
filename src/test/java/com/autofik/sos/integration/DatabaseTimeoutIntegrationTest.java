package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.repository.SosRequestRepository;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A ticket held by a stuck transaction must cost its callers a bounded wait and a 503, not a wait
 * with no end that eats a pool connection per caller (lock_timeout, set in the pool's init SQL).
 */
class DatabaseTimeoutIntegrationTest extends FlowTestSupport {

  @Autowired private SosRequestRepository sosRequestRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private HttpResponse<String> cancel(Driver d, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/cancel")
            .header("user-context", userContext(d.user().getUserId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  @Test
  void theSessionLimits_areSetOnEveryPoolConnection() {
    assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
    assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("15s");
  }

  @Test
  void aTicketHeldByAStuckTransaction_answersTheCallerWith503_andRecoversAfterwards()
      throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);
    AtomicReference<HttpResponse<String>> blocked = new AtomicReference<>();
    long[] waitedMs = new long[1];

    transactionTemplate.executeWithoutResult(
        status -> {
          sosRequestRepository.lockStatus(sosId); // the stuck transaction
          long start = System.nanoTime();
          try {
            blocked.set(cancel(d, sosId));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          waitedMs[0] = (System.nanoTime() - start) / 1_000_000;
        });

    finding(
        "Ticket lock held: cancel answered "
            + blocked.get().statusCode()
            + " after "
            + waitedMs[0]
            + " ms");
    assertThat(blocked.get().statusCode()).isEqualTo(503);
    assertThat(blocked.get().headers().firstValue("Retry-After")).contains("5");
    assertThat(waitedMs[0]).as("waited for the lock, then gave up").isBetween(4_000L, 12_000L);

    // The lock is released: the same call now goes through, without a restart.
    assertThat(cancel(d, sosId).statusCode()).isEqualTo(200);
  }
}
