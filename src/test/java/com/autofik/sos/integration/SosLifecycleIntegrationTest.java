package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.repository.MechanicSkillRepository;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import com.uber.h3core.H3Core;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

  private static final H3Core H3 = h3();

  @Autowired private MechanicSkillRepository mechanicSkillRepository;
  @Autowired private SosStatusHistoryRepository historyRepository;

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

  /**
   * A mechanic the matcher can find: parked at the point, with the H3 cell the location PATCH would
   * have written and one skill row carrying the given tag.
   */
  private Mechanic skilledMechanic(double lat, double lng, String skillTag) {
    Mechanic m = newMechanic(lat, lng);
    m.setCurrentH3Index(H3.geoToH3Address(lat, lng, 7));
    m = mechanicRepository.save(m);
    mechanicSkillRepository.save(
        MechanicSkill.builder().mechanic(m).skillTags(new String[] {skillTag}).build());
    return m;
  }

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

  private HttpResponse<String> placeBid(Mechanic m, UUID sosId) throws Exception {
    return send(
        req("/api/v1/bids")
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"mechanicId\":\""
                        + m.getMechanicId()
                        + "\",\"sosId\":\""
                        + sosId
                        + "\",\"price\":25.00}")));
  }

  private UUID placeBidOk(Mechanic m, UUID sosId) throws Exception {
    HttpResponse<String> r = placeBid(m, sosId);
    assertThat(r.statusCode()).as("place bid: %s", r.body()).isEqualTo(201);
    return UUID.fromString(parse(r.body()).get("bidId").asString());
  }

  private HttpResponse<String> acceptBid(Driver d, UUID bidId) throws Exception {
    return send(
        req("/api/v1/bids/" + bidId + "/status")
            .header("user-context", userContext(d.user().getUserId()))
            .header("Content-Type", "application/json")
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString(
                    "{\"userId\":\"" + d.user().getUserId() + "\",\"status\":\"ACCEPTED\"}")));
  }

  private void acceptBidOk(Driver d, UUID bidId) throws Exception {
    HttpResponse<String> r = acceptBid(d, bidId);
    assertThat(r.statusCode()).as("accept bid: %s", r.body()).isEqualTo(200);
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

  private static H3Core h3() {
    try {
      return H3Core.newInstance();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
