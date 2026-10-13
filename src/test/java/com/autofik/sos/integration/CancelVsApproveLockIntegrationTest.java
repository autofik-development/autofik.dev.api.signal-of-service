package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import com.autofik.sos.service.impl.SosServiceImpl;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A customer's answer to a waiting arrival or completion request, racing a cancel. The answer reads
 * the ticket before it waits for the ticket lock, so a cancel that commits in between used to be
 * overwritten by COMPLETED or ARRIVED. The cancel now closes the waiting request, and the answer,
 * which re-reads the request after the lock, refuses it.
 */
class CancelVsApproveLockIntegrationTest extends FlowTestSupport {

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

  private HttpResponse<String> cancelTicket(Driver d, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/cancel")
            .header("user-context", userContext(d.user().getUserId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  /** The id of the completion request the mechanic has put to the customer. */
  private UUID proposeCompletionOk(Mechanic m, UUID sosId) throws Exception {
    HttpResponse<String> r =
        send(
            req("/api/v1/sos/" + sosId + "/completion")
                .header("mechanic-context", mechanicContext(m.getMechanicId()))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"mechanicId\":\""
                            + m.getMechanicId()
                            + "\",\"toStatus\":\"COMPLETED\"}")));
    assertThat(r.statusCode()).as("propose completion: %s", r.body()).isEqualTo(201);
    return UUID.fromString(parse(r.body()).get("id").asString());
  }

  /** The ticket is IN_PROGRESS and the mechanic's completion request waits for the customer. */
  private UUID completionWaiting(Driver d, Mechanic m, UUID sosId) throws Exception {
    assignMechanic(d, m, sosId);
    assertThat(updateStatus(m, sosId, SosStatus.ON_THE_WAY).statusCode()).isEqualTo(200);
    arrive(d, m, sosId);
    assertThat(updateStatus(m, sosId, SosStatus.IN_PROGRESS).statusCode()).isEqualTo(200);
    return proposeCompletionOk(m, sosId);
  }

  /** The ticket is ON_THE_WAY and the mechanic's arrival request waits for the customer. */
  private UUID arrivalWaiting(Driver d, Mechanic m, UUID sosId) throws Exception {
    assignMechanic(d, m, sosId);
    assertThat(updateStatus(m, sosId, SosStatus.ON_THE_WAY).statusCode()).isEqualTo(200);
    return proposeArrivalOk(m, sosId);
  }

  private void awaitABlockedLock() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity"
                  + " WHERE wait_event_type = 'Lock' AND datname = current_database()",
              Integer.class);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(25);
    }
    throw new AssertionError("the customer's answer never waited for the ticket lock");
  }

  /**
   * The answer is held at the ticket lock, after it has already read the ticket; a cancel then runs
   * in the transaction that holds the lock and commits. Returns how the answer ended.
   */
  private ExecutionException cancelWhileTheAnswerWaits(Driver d, UUID sosId, UUID historyId) {
    CompletableFuture<Object> answer = new CompletableFuture<>();
    transactionTemplate.executeWithoutResult(
        status -> {
          sosRequestRepository.lockStatus(sosId); // what cancel takes first
          CompletableFuture.runAsync(
                  () ->
                      sosService.approveStatusChange(
                          new ApproveStatusChangeRequest(
                              d.user().getUserId(), historyId, ApprovedStatus.ACCEPTED)))
              .whenComplete(
                  (ok, ex) -> {
                    if (ex == null) answer.complete(ok);
                    else answer.completeExceptionally(ex);
                  });
          try {
            awaitABlockedLock();
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          sosService.cancel(sosId, d.user().getUserId()); // same transaction: CANCELLED, row closed
        });
    return catchThrowableOfType(ExecutionException.class, () -> answer.get(10, TimeUnit.SECONDS));
  }

  /** Status updates published for this ticket during the window, as the JSON the topic carries. */
  private List<String> statusEventsFor(KafkaConsumer<String, String> topic, UUID sosId) {
    List<String> toStatuses = new ArrayList<>();
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(4).toMillis();
    while (System.currentTimeMillis() < deadline) {
      for (ConsumerRecord<String, String> r : topic.poll(Duration.ofMillis(500))) {
        if (sosId.toString().equals(r.key())) {
          toStatuses.add(parse(r.value()).get("toStatus").asString());
        }
      }
    }
    return toStatuses;
  }

  @Test
  void aCancelWhileACompletionIsBeingApproved_wins_andNoCompletionIsApplied() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID historyId = completionWaiting(d, m, sosId);

    try (KafkaConsumer<String, String> statusTopic = tap("sos.status.updated")) {
      ExecutionException refused = cancelWhileTheAnswerWaits(d, sosId, historyId);

      assertThat(sosStatus(d, sosId)).as("the cancel must stand").isEqualTo("CANCELLED");
      assertThat(refused).hasCauseInstanceOf(IllegalStateException.class);
      assertThat(refused.getCause()).hasMessageContaining("already REJECTED");
      assertThat(historyRepository.findById(historyId).orElseThrow().getApprovedStatus())
          .isEqualTo(ApprovedStatus.REJECTED);
      assertThat(sosRequestRepository.findById(sosId).orElseThrow().getJobCompletedAt()).isNull();
      assertThat(statusEventsFor(statusTopic, sosId))
          .contains("CANCELLED")
          .doesNotContain("COMPLETED");
    }
  }

  @Test
  void aCancelWhileAnArrivalIsBeingApproved_wins_andNoArrivalIsApplied() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID historyId = arrivalWaiting(d, m, sosId);

    try (KafkaConsumer<String, String> statusTopic = tap("sos.status.updated")) {
      ExecutionException refused = cancelWhileTheAnswerWaits(d, sosId, historyId);

      assertThat(sosStatus(d, sosId)).as("the cancel must stand").isEqualTo("CANCELLED");
      assertThat(refused).hasCauseInstanceOf(IllegalStateException.class);
      assertThat(refused.getCause()).hasMessageContaining("already REJECTED");
      assertThat(historyRepository.findById(historyId).orElseThrow().getApprovedStatus())
          .isEqualTo(ApprovedStatus.REJECTED);
      assertThat(statusEventsFor(statusTopic, sosId))
          .contains("CANCELLED")
          .doesNotContain("ARRIVED");
    }
  }

  @Test
  void approveStatusChange_rejectedRow_returnsExpected409Contract() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID historyId = completionWaiting(d, m, sosId);
    assertThat(cancelTicket(d, sosId).statusCode()).isEqualTo(200);

    HttpResponse<String> answer = answerRequest(d, historyId, "ACCEPTED");

    assertThat(answer.statusCode()).as(answer.body()).isEqualTo(409);
    var body = parse(answer.body());
    assertThat(body.get("status").asInt()).isEqualTo(409);
    assertThat(body.get("error").asString()).isEqualTo("Conflict");
    assertThat(body.get("message").asString()).isEqualTo("This request was already REJECTED");
    assertThat(body.has("timestamp")).isTrue();
    assertThat(body.has("details")).isFalse();
    assertThat(sosStatus(d, sosId)).isEqualTo("CANCELLED");
  }
}
