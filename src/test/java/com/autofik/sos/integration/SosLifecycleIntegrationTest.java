package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import com.autofik.sos.service.impl.MechanicLocationWriter;
import com.autofik.sos.service.impl.SosServiceImpl;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;
import tools.jackson.databind.JsonNode;

/**
 * The whole job, end to end over REST: SOS created, mechanic found by the geo search, bid placed
 * and accepted, the mechanic drives the ticket to IN_PROGRESS, completion is proposed and approved,
 * the driver rates the job. Each test makes its own driver and mechanics, so the shared database
 * only ever grows; assertions about lists check membership, never exact size.
 */
class SosLifecycleIntegrationTest extends FlowTestSupport {

  // The point createBody() sends; a mechanic parked here passes the ARRIVED distance check.
  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  // FlowTestSupport stubs Medusa to resolve every service group to this problem type.
  private static final String PROBLEM_TYPE = "TOW";

  @Autowired private SosStatusHistoryRepository historyRepository;
  @Autowired private SosRequestRepository sosRequestRepository;
  @Autowired private SosServiceImpl sosService;
  @Autowired private MechanicLocationWriter locationWriter;

  @Test
  void fullLifecycle_reachesCompleted_andRecordsEveryMechanicDrivenTransition() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    assertThat(sosStatus(d, sosId)).isEqualTo("PENDING");

    UUID bidId = placeBidOk(m, sosId);
    acceptBidOk(d, bidId);
    assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");

    for (SosStatus next : List.of(SosStatus.ON_THE_WAY, SosStatus.ARRIVED, SosStatus.IN_PROGRESS)) {
      HttpResponse<String> r = updateStatus(m, sosId, next);
      assertThat(r.statusCode()).as("%s: %s", next, r.body()).isEqualTo(200);
      assertThat(parse(r.body()).get("status").asString()).isEqualTo(next.name());
    }

    UUID historyId = proposeCompletionOk(m, sosId);
    assertThat(sosStatus(d, sosId))
        .as("a proposal alone must not complete the job")
        .isEqualTo("IN_PROGRESS");

    HttpResponse<String> approved = approveCompletion(d, historyId, ApprovedStatus.ACCEPTED);
    assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
    assertThat(sosStatus(d, sosId)).isEqualTo("COMPLETED");

    List<SosStatusHistory> history =
        historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId);
    List<String> transitions = new ArrayList<>();
    for (SosStatusHistory h : history) transitions.add(h.getFromStatus() + ">" + h.getToStatus());
    assertThat(transitions)
        .containsExactly(
            "PENDING>ACCEPTED",
            "ACCEPTED>ON_THE_WAY",
            "ON_THE_WAY>ARRIVED",
            "ARRIVED>IN_PROGRESS",
            "IN_PROGRESS>COMPLETED");
    assertThat(history).allMatch(h -> h.getApprovedStatus() == ApprovedStatus.ACCEPTED);
  }

  /**
   * Pins the scope of {@code sos_status_history}: it records the transitions a mechanic proposes
   * and the bid accept, not the ones the driver or the system make. Cancel, expiry and rebroadcast
   * have no mechanic, and {@code proposed_by} is NOT NULL, so they write no row; they show through
   * the ticket's {@code status} and the {@code sos.status.updated} event instead. If this test
   * starts failing, someone changed that on purpose: update the docs and the entity's Javadoc with
   * it.
   */
  @Test
  void cancelExpiryAndRebroadcast_writeNoStatusHistory() throws Exception {
    // Cancel (PENDING -> CANCELLED), by the driver.
    Driver cancelling = newDriver();
    UUID cancelled = createSosOk(cancelling);
    HttpResponse<String> cancel =
        send(
            req("/api/v1/sos/" + cancelled + "/cancel")
                .header("user-context", userContext(cancelling.user().getUserId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);
    assertThat(sosStatus(cancelling, cancelled)).isEqualTo("CANCELLED");
    assertThat(historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(cancelled)).isEmpty();

    // Expiry (PENDING -> EXPIRED) by the system, then rebroadcast (EXPIRED -> PENDING) by the
    // driver. A bid is placed first: a ticket with no bids widens its search instead of expiring.
    Driver expiring = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(expiring);
    placeBidOk(m, sosId);
    var sos = sosRequestRepository.findById(sosId).orElseThrow();
    sos.setBiddingExpiresAt(LocalDateTime.now().minusMinutes(1));
    sosRequestRepository.save(sos);

    sosService.expireOverduePendingRequests();
    assertThat(sosStatus(expiring, sosId)).isEqualTo("EXPIRED");
    assertThat(historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId)).isEmpty();

    HttpResponse<String> rebroadcast =
        send(
            req("/api/v1/sos/" + sosId + "/rebroadcast")
                .header("user-context", userContext(expiring.user().getUserId()))
                .POST(HttpRequest.BodyPublishers.noBody()));
    assertThat(rebroadcast.statusCode()).as(rebroadcast.body()).isEqualTo(200);
    assertThat(sosStatus(expiring, sosId)).isEqualTo("PENDING");
    assertThat(historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId)).isEmpty();
  }

  /**
   * A bid stays ACCEPTED after the job is done. The mechanic's "active job" must still be the
   * current one: location pings with no sosId go to the second ticket, and the second driver (not
   * the first) may read the mechanic's position.
   */
  @Test
  void aMechanicsSecondJob_isTheOneTrackedAndVisibleToItsDriver() throws Exception {
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Driver first = newDriver();
    completedSos(first, m);

    Driver second = newDriver();
    UUID secondSos = createSosOk(second);
    acceptBidOk(second, placeBidOk(m, secondSos));

    assertThat(locationWriter.persistAndResolveSos(m.getMechanicId(), SOS_LAT, SOS_LNG, null))
        .as("a ping with no sosId must resolve to the current job, not the finished one")
        .isEqualTo(secondSos);
    assertThat(mechanicLocationStatus(second, m)).as("current driver").isEqualTo(200);
    assertThat(mechanicLocationStatus(first, m)).as("driver of the finished job").isEqualTo(403);
  }

  /** A ping's sosId is only trusted when the mechanic holds the accepted bid on that active SOS. */
  @Test
  void aPingWithSomeoneElsesSosId_isNotBroadcastOnThatSos() throws Exception {
    Mechanic assigned = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic outsider = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Driver d = newDriver();
    UUID sosId = createSosOk(d);
    acceptBidOk(d, placeBidOk(assigned, sosId));

    assertThat(
            locationWriter.persistAndResolveSos(outsider.getMechanicId(), SOS_LAT, SOS_LNG, sosId))
        .as("the outsider has no job, so nothing may be broadcast")
        .isNull();
    assertThat(
            locationWriter.persistAndResolveSos(assigned.getMechanicId(), SOS_LAT, SOS_LNG, sosId))
        .as("the assigned mechanic's own sosId is accepted")
        .isEqualTo(sosId);
  }

  private int mechanicLocationStatus(Driver d, Mechanic m) throws Exception {
    return send(req("/api/v1/mechanics/" + m.getMechanicId() + "/location")
            .header("user-context", userContext(d.user().getUserId()))
            .GET())
        .statusCode();
  }

  /**
   * The historyId needed to approve a completion used to arrive only in one WebSocket frame. A
   * customer who missed it (app closed, tapped the push) could not approve, and the ticket sat in
   * IN_PROGRESS, blocking a new SOS. The owner now reads it from GET /sos/{id}.
   */
  @Test
  void aWaitingCompletion_isReadableByTheOwner_andARetriedProposalIsNotDuplicated()
      throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = inProgressSos(d, m);
    assertThat(pendingCompletionSeenBy(userContextHeader(d), sosId))
        .as("nothing proposed")
        .isNull();

    UUID historyId = proposeCompletionOk(m, sosId);
    assertThat(pendingCompletionSeenBy(userContextHeader(d), sosId))
        .as("the owner can read the waiting request")
        .isEqualTo(historyId);
    assertThat(pendingCompletionSeenBy(mechanicContextHeader(m), sosId))
        .as("the mechanic is not shown it")
        .isNull();

    assertThat(proposeCompletionOk(m, sosId))
        .as("a retried proposal returns the request that is already waiting")
        .isEqualTo(historyId);
    assertThat(
            historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId).stream()
                .filter(h -> h.getApprovedStatus() == ApprovedStatus.PENDING_APPROVAL)
                .count())
        .isEqualTo(1);

    assertThat(approveCompletion(d, historyId, ApprovedStatus.REJECTED).statusCode())
        .isEqualTo(200);
    assertThat(pendingCompletionSeenBy(userContextHeader(d), sosId))
        .as("a rejected request is no longer waiting")
        .isNull();

    UUID second = proposeCompletionOk(m, sosId);
    assertThat(second).as("after a rejection a new request is created").isNotEqualTo(historyId);
    assertThat(pendingCompletionSeenBy(userContextHeader(d), sosId)).isEqualTo(second);
    assertThat(approveCompletion(d, second, ApprovedStatus.ACCEPTED).statusCode()).isEqualTo(200);
    assertThat(pendingCompletionSeenBy(userContextHeader(d), sosId))
        .as("nothing is waiting once the job is completed")
        .isNull();
  }

  /**
   * A cancelled job used to reach the assigned mechanic never: they kept driving. They now get a
   * frame on their private queue, and a mechanic who only had a pending bid is told too.
   */
  @Test
  void aCancelledJob_isPushedToTheAssignedMechanic_andToPendingBidders() throws Exception {
    Driver d = newDriver();
    Mechanic assigned = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic bidder = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    placeBidOk(bidder, sosId);
    acceptBidOk(d, placeBidOk(assigned, sosId));
    // The accept above rejects the bidder's bid; a fresh pending bid needs its own ticket, so the
    // pending-bidder case is covered separately below.

    StompSession mechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(assigned.getMechanicId()));
    StompSession bystander =
        stomp.connect(port, "mechanic-context", mechanicContext(bidder.getMechanicId()));
    Tap frames = subscribe(mechanic, "/user/queue/sos-updates");
    Tap other = subscribe(bystander, "/user/queue/sos-updates");
    awaitSubscriptions("/user/queue/sos-updates", 2);

    HttpResponse<String> cancel =
        send(
            req("/api/v1/sos/" + sosId + "/cancel")
                .header("user-context", userContextHeader(d))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);

    JsonNode frame = awaitTypedFrame(frames, sosId, "SOS_CANCELLED", Duration.ofSeconds(10));
    assertThat(frame.get("type").asString()).isEqualTo("SOS_CANCELLED");
    assertThat(frame.get("fromStatus").asString()).isEqualTo("ACCEPTED");
    assertThat(other.next(Duration.ofSeconds(1)))
        .as("a mechanic whose bid was already rejected is not told")
        .isNull();
    mechanic.disconnect();
    bystander.disconnect();
  }

  @Test
  void aCancelledRequest_isPushedToMechanicsWithAPendingBid() throws Exception {
    Driver d = newDriver();
    Mechanic bidder = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic outsider = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    placeBidOk(bidder, sosId);

    StompSession bidderSession =
        stomp.connect(port, "mechanic-context", mechanicContext(bidder.getMechanicId()));
    StompSession outsiderSession =
        stomp.connect(port, "mechanic-context", mechanicContext(outsider.getMechanicId()));
    Tap frames = subscribe(bidderSession, "/user/queue/sos-updates");
    Tap other = subscribe(outsiderSession, "/user/queue/sos-updates");
    awaitSubscriptions("/user/queue/sos-updates", 2);

    HttpResponse<String> cancel =
        send(
            req("/api/v1/sos/" + sosId + "/cancel")
                .header("user-context", userContextHeader(d))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);

    JsonNode frame = awaitTypedFrame(frames, sosId, "SOS_CANCELLED", Duration.ofSeconds(10));
    assertThat(frame.get("type").asString()).isEqualTo("SOS_CANCELLED");
    assertThat(frame.get("fromStatus").asString()).isEqualTo("PENDING");
    assertThat(other.next(Duration.ofSeconds(1))).as("a mechanic who never bid").isNull();
    bidderSession.disconnect();
    outsiderSession.disconnect();
  }

  /**
   * A customer who never answers must not leave the job open: it blocks a new SOS and keeps the
   * mechanic tied to it. After the approval window the sweep approves the request as the system.
   */
  @Test
  void anUnansweredCompletion_isApprovedAutomatically_afterTheWindow() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = inProgressSos(d, m);
    UUID historyId = proposeCompletionOk(m, sosId);

    HttpResponse<String> before =
        send(req("/api/v1/sos/" + sosId).header("user-context", userContextHeader(d)).GET());
    assertThat(parse(before.body()).get("pendingCompletionApproveBy").isNull())
        .as("the owner is shown when the request will be approved automatically")
        .isFalse();

    // Inside the window nothing happens.
    sosService.approveOverdueCompletions();
    assertThat(sosStatus(d, sosId)).isEqualTo("IN_PROGRESS");

    SosStatusHistory row = historyRepository.findById(historyId).orElseThrow();
    row.setProposedAt(LocalDateTime.now().minusMinutes(5));
    historyRepository.save(row);

    sosService.approveOverdueCompletions();

    assertThat(sosStatus(d, sosId)).isEqualTo("COMPLETED");
    SosStatusHistory decided = historyRepository.findById(historyId).orElseThrow();
    assertThat(decided.getApprovedStatus()).isEqualTo(ApprovedStatus.ACCEPTED);
    assertThat(decided.getApprovedBy()).as("approved by the system").isNull();
    assertThat(pendingCompletionSeenBy(userContextHeader(d), sosId)).isNull();
    assertThat(approveCompletion(d, historyId, ApprovedStatus.ACCEPTED).statusCode())
        .as("the customer answering afterwards is told it was already decided")
        .isEqualTo(409);
  }

  private String userContextHeader(Driver d) {
    return userContext(d.user().getUserId());
  }

  private String mechanicContextHeader(Mechanic m) {
    return mechanicContext(m.getMechanicId());
  }

  /** The pendingCompletionHistoryId GET /sos/{id} shows to the caller, or null. */
  private UUID pendingCompletionSeenBy(String contextHeaderValue, UUID sosId) throws Exception {
    boolean mechanic = contextHeaderValue.contains("\"mechanic\"");
    HttpResponse<String> r =
        send(
            req("/api/v1/sos/" + sosId)
                .header(mechanic ? "mechanic-context" : "user-context", contextHeaderValue)
                .GET());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    JsonNode node = parse(r.body()).get("pendingCompletionHistoryId");
    return node == null || node.isNull() ? null : UUID.fromString(node.asString());
  }

  @Test
  void completedCannotBeSetDirectly_is400_andTheTicketDoesNotMove() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    acceptBidOk(d, placeBidOk(m, sosId));

    HttpResponse<String> r = updateStatus(m, sosId, SosStatus.COMPLETED);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
    assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");
  }

  @Test
  void skippingAState_is409() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    acceptBidOk(d, placeBidOk(m, sosId));

    HttpResponse<String> r = updateStatus(m, sosId, SosStatus.ARRIVED); // ACCEPTED -> ARRIVED

    assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
    assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");
  }

  @Test
  void arrivedFromFarAway_is409() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(11.5600, 104.9300, PROBLEM_TYPE); // ~450 m from the SOS
    UUID sosId = createSosOk(d);
    acceptBidOk(d, placeBidOk(m, sosId));
    assertThat(updateStatus(m, sosId, SosStatus.ON_THE_WAY).statusCode()).isEqualTo(200);

    HttpResponse<String> r = updateStatus(m, sosId, SosStatus.ARRIVED);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
    assertThat(sosStatus(d, sosId)).isEqualTo("ON_THE_WAY");
  }

  @Test
  void aMechanicWhoWasNotAssigned_cannotMoveTheTicket() throws Exception {
    Driver d = newDriver();
    Mechanic assigned = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic stranger = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    acceptBidOk(d, placeBidOk(assigned, sosId));

    HttpResponse<String> r = updateStatus(stranger, sosId, SosStatus.ON_THE_WAY);

    assertThat(r.statusCode()).as(r.body()).isIn(403, 409);
    assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");
  }

  @Test
  void rejectedCompletion_leavesTheJobInProgress() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = inProgressSos(d, m);

    UUID historyId = proposeCompletionOk(m, sosId);
    HttpResponse<String> r = approveCompletion(d, historyId, ApprovedStatus.REJECTED);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(sosStatus(d, sosId)).isEqualTo("IN_PROGRESS");
    // A decided proposal cannot be flipped afterwards.
    assertThat(approveCompletion(d, historyId, ApprovedStatus.ACCEPTED).statusCode())
        .isEqualTo(409);
    assertThat(sosStatus(d, sosId)).isEqualTo("IN_PROGRESS");
  }

  @Test
  void anotherDriver_cannotApproveSomeoneElsesCompletion() throws Exception {
    Driver owner = newDriver();
    Driver other = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = inProgressSos(owner, m);
    UUID historyId = proposeCompletionOk(m, sosId);

    HttpResponse<String> r = approveCompletion(other, historyId, ApprovedStatus.ACCEPTED);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
    assertThat(sosStatus(owner, sosId)).isEqualTo("IN_PROGRESS");
  }

  // ── bids ───────────────────────────────────────────────────────────────────

  @Test
  void acceptingOneBid_rejectsTheOthers_andTheAcceptedBidCannotBeWithdrawn() throws Exception {
    Driver d = newDriver();
    Mechanic winner = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic loser = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID winningBid = placeBidOk(winner, sosId);
    UUID losingBid = placeBidOk(loser, sosId);

    acceptBidOk(d, winningBid);

    assertThat(bidStatuses(d, sosId))
        .containsEntry(winningBid.toString(), "ACCEPTED")
        .containsEntry(losingBid.toString(), "REJECTED");

    HttpResponse<String> withdraw = cancelBid(winner, winningBid);
    assertThat(withdraw.statusCode()).as(withdraw.body()).isEqualTo(409);
    assertThat(bidStatuses(d, sosId)).containsEntry(winningBid.toString(), "ACCEPTED");
    assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");
  }

  @Test
  void aPendingBid_canBeWithdrawnByItsMechanic_butNotByAnother() throws Exception {
    Driver d = newDriver();
    Mechanic owner = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic other = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID bidId = placeBidOk(owner, sosId);

    assertThat(cancelBid(other, bidId).statusCode()).isEqualTo(403);

    HttpResponse<String> r = cancelBid(owner, bidId);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(parse(r.body()).get("status").asString()).isEqualTo("CANCELLED");
  }

  @Test
  void secondBidFromTheSameMechanic_is409() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    placeBidOk(m, sosId);

    HttpResponse<String> r = placeBid(m, sosId);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
  }

  @Test
  void bidOnAnAcceptedSos_is409() throws Exception {
    Driver d = newDriver();
    Mechanic first = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic late = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    acceptBidOk(d, placeBidOk(first, sosId));

    HttpResponse<String> r = placeBid(late, sosId);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
  }

  /**
   * Bids arriving while the driver accepts must either be rejected with the rest or refused. Before
   * the ticket row was locked, a bid that read PENDING just before the accept committed was
   * inserted after the auto-reject and stayed PENDING on an accepted ticket (found by the bidding
   * load test).
   */
  @Test
  void bidsRacingTheAccept_leaveNoPendingBidOnTheAcceptedTicket() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      for (int round = 0; round < 5; round++) {
        Driver d = newDriver();
        UUID sosId = createSosOk(d);
        UUID firstBid = placeBidOk(skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE), sosId);
        List<Mechanic> late = new ArrayList<>();
        for (int i = 0; i < 24; i++) late.add(skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE));

        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> bids = new ArrayList<>();
        for (Mechanic m : late) {
          bids.add(
              pool.submit(
                  () -> {
                    go.await();
                    return placeBid(m, sosId).statusCode();
                  }));
        }
        Future<Integer> accept =
            pool.submit(
                () -> {
                  go.await();
                  Thread.sleep(5); // land in the middle of the bid burst
                  return acceptBid(d, firstBid).statusCode();
                });
        go.countDown();
        for (Future<Integer> b : bids) assertThat(b.get(30, TimeUnit.SECONDS)).isIn(201, 409);
        assertThat(accept.get(30, TimeUnit.SECONDS)).isEqualTo(200);

        assertThat(bidStatuses(d, sosId).values())
            .as("round %d: bids on a ticket that is already ACCEPTED", round)
            .doesNotContain("PENDING")
            .containsOnlyOnce("ACCEPTED");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void twoConcurrentAccepts_onDifferentBids_onlyOneWins() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 5; round++) {
        Driver d = newDriver();
        UUID sosId = createSosOk(d);
        UUID a = placeBidOk(skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE), sosId);
        UUID b = placeBidOk(skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE), sosId);

        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> ra =
            pool.submit(
                () -> {
                  go.await();
                  return acceptBid(d, a).statusCode();
                });
        Future<Integer> rb =
            pool.submit(
                () -> {
                  go.await();
                  return acceptBid(d, b).statusCode();
                });
        go.countDown();

        assertThat(List.of(ra.get(30, TimeUnit.SECONDS), rb.get(30, TimeUnit.SECONDS)))
            .as("round %d", round)
            .containsExactlyInAnyOrder(200, 409);
        assertThat(bidStatuses(d, sosId).values()).containsOnlyOnce("ACCEPTED");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ── geo search ─────────────────────────────────────────────────────────────

  @Test
  void nearbyMechanics_areMatchedByH3RadiusSkillAndAvailability() throws Exception {
    Driver d = newDriver();
    Mechanic near = skilledMechanic(11.5600, 104.9300, PROBLEM_TYPE); // ~0.5 km
    Mechanic mid = skilledMechanic(11.5900, 104.9500, PROBLEM_TYPE); // ~4.5 km
    Mechanic far = skilledMechanic(11.7000, 105.1000, PROBLEM_TYPE); // ~25 km, outside 10 km
    Mechanic wrongSkill = skilledMechanic(11.5600, 104.9300, "TIRE");
    Mechanic offDuty = skilledMechanic(11.5600, 104.9300, PROBLEM_TYPE);
    offDuty.setAvailableStatus(false);
    mechanicRepository.save(offDuty);

    UUID sosId = createSosOk(d); // broadcastRadiusKm = 10

    HttpResponse<String> r =
        send(
            req("/api/v1/sos/" + sosId + "/nearby-mechanics")
                .header("user-context", userContext(d.user().getUserId()))
                .GET());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    List<String> ids = new ArrayList<>();
    for (JsonNode n : parse(r.body())) ids.add(n.get("mechanicId").asString());

    assertThat(ids)
        .contains(near.getMechanicId().toString(), mid.getMechanicId().toString())
        .doesNotContain(
            far.getMechanicId().toString(),
            wrongSkill.getMechanicId().toString(),
            offDuty.getMechanicId().toString());
  }

  @Test
  void nearbyMechanics_ofSomeoneElsesSos_is404() throws Exception {
    Driver owner = newDriver();
    Driver stranger = newDriver();
    UUID sosId = createSosOk(owner);

    HttpResponse<String> r =
        send(
            req("/api/v1/sos/" + sosId + "/nearby-mechanics")
                .header("user-context", userContext(stranger.user().getUserId()))
                .GET());

    assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
  }

  // ── WebSocket ──────────────────────────────────────────────────────────────

  @Test
  void statusChange_isPushedToTheDriversPrivateQueue_andNotToAnotherDriver() throws Exception {
    Driver d = newDriver();
    Driver other = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    acceptBidOk(d, placeBidOk(m, sosId));

    StompSession driver = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    StompSession bystander =
        stomp.connect(port, "user-context", userContext(other.user().getUserId()));
    Tap updates = subscribe(driver, "/user/queue/sos-updates");
    Tap leaked = subscribe(bystander, "/user/queue/sos-updates");
    awaitSubscriptions("/user/queue/sos-updates", 2);

    assertThat(updateStatus(m, sosId, SosStatus.ON_THE_WAY).statusCode()).isEqualTo(200);

    JsonNode event = awaitFrame(updates, sosId, Duration.ofSeconds(10));
    assertThat(event.get("fromStatus").asString()).isEqualTo("ACCEPTED");
    assertThat(event.get("toStatus").asString()).isEqualTo("ON_THE_WAY");
    assertThat(event.get("mechanicId").asString()).isEqualTo(m.getMechanicId().toString());
    assertThat(leaked.next(Duration.ofSeconds(1))).as("another driver's queue").isNull();

    driver.disconnect();
    bystander.disconnect();
  }

  // ── feedback ───────────────────────────────────────────────────────────────

  @Test
  void feedback_beforeCompletion_is409() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = inProgressSos(d, m);

    HttpResponse<String> r = submitFeedback(d, m, sosId, "5");

    assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
  }

  @Test
  void feedback_onACompletedJob_isStored_updatesTheMechanicsRating_andCannotBeRepeated()
      throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = completedSos(d, m);

    HttpResponse<String> created = submitFeedback(d, m, sosId, "4");
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

    HttpResponse<String> read =
        send(
            req("/api/v1/feedback/sos/" + sosId)
                .header("user-context", userContext(d.user().getUserId()))
                .GET());
    assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
    JsonNode fb = parse(read.body());
    assertThat(fb.get("rating").decimalValue()).isEqualByComparingTo("4");
    assertThat(fb.get("comment").asString()).isEqualTo("Great service");
    assertThat(fb.get("mechanic").get("avgRate").decimalValue()).isEqualByComparingTo("4");
    assertThat(fb.get("mechanic").get("rateCount").asInt()).isEqualTo(1);

    assertThat(submitFeedback(d, m, sosId, "1").statusCode()).isEqualTo(409);
  }

  @Test
  void feedback_ratingOutsideOneToFive_is400() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = completedSos(d, m);

    assertThat(submitFeedback(d, m, sosId, "0").statusCode()).isEqualTo(400);
    assertThat(submitFeedback(d, m, sosId, "6").statusCode()).isEqualTo(400);
  }

  @Test
  void feedback_fromADriverWhoDoesNotOwnTheSos_is403() throws Exception {
    Driver owner = newDriver();
    Driver other = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = completedSos(owner, m);

    HttpResponse<String> r = submitFeedback(other, m, sosId, "1");

    assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private UUID inProgressSos(Driver d, Mechanic m) throws Exception {
    UUID sosId = createSosOk(d);
    acceptBidOk(d, placeBidOk(m, sosId));
    for (SosStatus s : List.of(SosStatus.ON_THE_WAY, SosStatus.ARRIVED, SosStatus.IN_PROGRESS)) {
      HttpResponse<String> r = updateStatus(m, sosId, s);
      assertThat(r.statusCode()).as("%s: %s", s, r.body()).isEqualTo(200);
    }
    return sosId;
  }

  private UUID completedSos(Driver d, Mechanic m) throws Exception {
    UUID sosId = inProgressSos(d, m);
    HttpResponse<String> r =
        approveCompletion(d, proposeCompletionOk(m, sosId), ApprovedStatus.ACCEPTED);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return sosId;
  }

  private String sosStatus(Driver d, UUID sosId) throws Exception {
    HttpResponse<String> r =
        send(
            req("/api/v1/sos/" + sosId)
                .header("user-context", userContext(d.user().getUserId()))
                .GET());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return parse(r.body()).get("status").asString();
  }

  private HttpResponse<String> cancelBid(Mechanic m, UUID bidId) throws Exception {
    return send(
        req("/api/v1/bids/" + bidId + "/cancel")
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  /** bidId -> status for every bid on the ticket, as the driver sees them. */
  private java.util.Map<String, String> bidStatuses(Driver d, UUID sosId) throws Exception {
    HttpResponse<String> r =
        send(
            req("/api/v1/bids/sos/" + sosId)
                .header("user-context", userContext(d.user().getUserId()))
                .GET());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    java.util.Map<String, String> out = new java.util.HashMap<>();
    for (JsonNode b : parse(r.body()))
      out.put(b.get("bidId").asString(), b.get("status").asString());
    return out;
  }

  private HttpResponse<String> release(Mechanic m, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/mechanic-release")
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  /**
   * The assigned mechanic backs out: the ticket is open for bidding again, the customer is told,
   * everyone but the releaser is offered the job, a mechanic whose bid was auto-rejected at the
   * accept can bid again, and the history records the release.
   */
  @Test
  void aReleasedJob_goesBackToBidding_andIsOfferedToEveryoneButTheReleaser() throws Exception {
    Driver d = newDriver();
    Mechanic releaser = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic other = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID otherBid = placeBidOk(other, sosId);
    acceptBidOk(d, placeBidOk(releaser, sosId));
    assertThat(updateStatus(releaser, sosId, SosStatus.ON_THE_WAY).statusCode()).isEqualTo(200);

    StompSession customer = stomp.connect(port, "user-context", userContextHeader(d));
    StompSession otherMechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(other.getMechanicId()));
    StompSession releasingMechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(releaser.getMechanicId()));
    Tap customerFrames = subscribe(customer, "/user/queue/sos-updates");
    Tap otherOffers = subscribe(otherMechanic, "/user/queue/sos-new-request");
    Tap releaserOffers = subscribe(releasingMechanic, "/user/queue/sos-new-request");
    awaitSubscriptions("/user/queue/sos-updates", 1);
    awaitSubscriptions("/user/queue/sos-new-request", 2);

    HttpResponse<String> released = release(releaser, sosId);
    assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
    assertThat(sosStatus(d, sosId)).isEqualTo("PENDING");

    JsonNode frame =
        awaitTypedFrame(customerFrames, sosId, "MECHANIC_RELEASED", Duration.ofSeconds(10));
    assertThat(frame.get("fromStatus").asString()).isEqualTo("ON_THE_WAY");
    assertThat(awaitTypedFrame(otherOffers, sosId, "SOS_CREATED", Duration.ofSeconds(10)))
        .as("the other mechanic is offered the job again")
        .isNotNull();
    assertThat(releaserOffers.next(Duration.ofSeconds(1)))
        .as("the mechanic who released it is not offered it again")
        .isNull();

    List<String> transitions = new ArrayList<>();
    for (SosStatusHistory h : historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId)) {
      transitions.add(h.getFromStatus() + ">" + h.getToStatus());
    }
    assertThat(transitions)
        .containsExactly("PENDING>ACCEPTED", "ACCEPTED>ON_THE_WAY", "ON_THE_WAY>PENDING");

    assertThat(placeBid(releaser, sosId).statusCode())
        .as("the mechanic who released it cannot bid on it again")
        .isEqualTo(409);
    UUID rebid = placeBidOk(other, sosId);
    assertThat(rebid).as("the rejected mechanic bids afresh").isNotEqualTo(otherBid);
    acceptBidOk(d, rebid);
    assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");
    assertThat(updateStatus(other, sosId, SosStatus.ON_THE_WAY).statusCode()).isEqualTo(200);

    customer.disconnect();
    otherMechanic.disconnect();
    releasingMechanic.disconnect();
  }

  @Test
  void aJobCannotBeReleasedFromArrived_orByAnotherMechanic() throws Exception {
    Driver d = newDriver();
    Mechanic assigned = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic outsider = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    assignMechanic(d, assigned, sosId);

    assertThat(release(outsider, sosId).statusCode()).isEqualTo(403);
    assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");

    for (SosStatus next : List.of(SosStatus.ON_THE_WAY, SosStatus.ARRIVED)) {
      assertThat(updateStatus(assigned, sosId, next).statusCode()).isEqualTo(200);
    }
    HttpResponse<String> late = release(assigned, sosId);
    assertThat(late.statusCode()).as(late.body()).isEqualTo(409);
    assertThat(sosStatus(d, sosId)).isEqualTo("ARRIVED");
  }

  /**
   * The mechanic releases at the same instant as they mark ARRIVED. Both take the ticket lock, so
   * exactly one wins and the ticket never ends ARRIVED with its accepted bid cancelled.
   */
  @Test
  void releaseAgainstArrived_exactlyOneWins() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 5; round++) {
        Driver d = newDriver();
        Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
        UUID sosId = createSosOk(d);
        assignMechanic(d, m, sosId);
        assertThat(updateStatus(m, sosId, SosStatus.ON_THE_WAY).statusCode()).isEqualTo(200);

        CountDownLatch go = new CountDownLatch(1);
        Future<HttpResponse<String>> arrive =
            pool.submit(
                () -> {
                  go.await();
                  return updateStatus(m, sosId, SosStatus.ARRIVED);
                });
        Future<HttpResponse<String>> leave =
            pool.submit(
                () -> {
                  go.await();
                  return release(m, sosId);
                });
        go.countDown();
        int arrived = arrive.get(30, TimeUnit.SECONDS).statusCode();
        int left = leave.get(30, TimeUnit.SECONDS).statusCode();

        assertThat(List.of(arrived, left).stream().filter(c -> c == 200).count())
            .as("round %d: arrived=%d released=%d", round, arrived, left)
            .isEqualTo(1);
        assertThat(sosStatus(d, sosId)).isEqualTo(arrived == 200 ? "ARRIVED" : "PENDING");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private HttpResponse<String> updateStatus(Mechanic m, UUID sosId, SosStatus to) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/status?toStatus=" + to)
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

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
    JsonNode body = parse(r.body());
    assertThat(body.get("approvedStatus").asString()).isEqualTo("PENDING_APPROVAL");
    return UUID.fromString(body.get("id").asString());
  }

  private HttpResponse<String> approveCompletion(Driver d, UUID historyId, ApprovedStatus decision)
      throws Exception {
    return send(
        req("/api/v1/sos/completion")
            .header("user-context", userContext(d.user().getUserId()))
            .header("Content-Type", "application/json")
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString(
                    "{\"userId\":\""
                        + d.user().getUserId()
                        + "\",\"historyId\":\""
                        + historyId
                        + "\",\"approvedStatus\":\""
                        + decision
                        + "\"}")));
  }

  private HttpResponse<String> submitFeedback(Driver d, Mechanic m, UUID sosId, String rating)
      throws Exception {
    return send(
        req("/api/v1/feedback")
            .header("user-context", userContext(d.user().getUserId()))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"userId\":\""
                        + d.user().getUserId()
                        + "\",\"mechanicId\":\""
                        + m.getMechanicId()
                        + "\",\"sosId\":\""
                        + sosId
                        + "\",\"rating\":"
                        + rating
                        + ",\"comment\":\"Great service\"}")));
  }

  /** The driver's queue also carries other events; skip to the status event for this ticket. */
  private static JsonNode awaitFrame(Tap tap, UUID sosId, Duration wait) throws Exception {
    long deadline = System.currentTimeMillis() + wait.toMillis();
    while (System.currentTimeMillis() < deadline) {
      String frame = tap.next(Duration.ofMillis(deadline - System.currentTimeMillis()));
      if (frame == null) break;
      JsonNode n = parse(frame);
      if (n.has("toStatus") && sosId.toString().equals(n.get("sosId").asString())) return n;
    }
    throw new AssertionError("no status event for " + sosId + " within " + wait);
  }

  /**
   * Skips to the frame of the given {@code type} for this ticket (frames without a type are
   * ignored).
   */
  private static JsonNode awaitTypedFrame(Tap tap, UUID sosId, String type, Duration wait)
      throws Exception {
    long deadline = System.currentTimeMillis() + wait.toMillis();
    while (System.currentTimeMillis() < deadline) {
      String frame = tap.next(Duration.ofMillis(deadline - System.currentTimeMillis()));
      if (frame == null) break;
      JsonNode n = parse(frame);
      if (n.has("type")
          && type.equals(n.get("type").asString())
          && n.has("sosId")
          && sosId.toString().equals(n.get("sosId").asString())) {
        return n;
      }
    }
    throw new AssertionError("no " + type + " frame for " + sosId + " within " + wait);
  }
}
