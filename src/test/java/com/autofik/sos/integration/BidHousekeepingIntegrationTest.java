package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.impl.SosServiceImpl;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;
import tools.jackson.databind.JsonNode;

/**
 * What happens to bids when the request ends, and who may bid again: a cancel and an expiry close
 * the bids and tell the bidders, a rebroadcast starts a clean round, a withdrawn bid can be placed
 * again, a released job cannot, and a customer's "no" to one bid reaches that mechanic.
 */
class BidHousekeepingIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";
  private static final String UPDATES = "/user/queue/sos-updates";
  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired private BidSosRepository bidSosRepository;
  @Autowired private SosRequestRepository sosRequestRepository;
  @Autowired private SosServiceImpl sosService;

  private List<BidSos> bidsOn(UUID sosId) {
    return bidSosRepository.findBySosRequestSosId(sosId);
  }

  private BidStatus statusOf(UUID bidId) {
    return bidSosRepository.findById(bidId).orElseThrow().getStatus();
  }

  private static JsonNode awaitFrame(Tap tap, UUID sosId, String type) throws Exception {
    long deadline = System.currentTimeMillis() + WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      String frame = tap.next(Duration.ofMillis(deadline - System.currentTimeMillis()));
      if (frame == null) break;
      JsonNode n = parse(frame);
      if (n.has("type")
          && type.equals(n.get("type").asString())
          && sosId.toString().equals(n.get("sosId").asString())) {
        return n;
      }
    }
    throw new AssertionError("no " + type + " frame for " + sosId + " within " + WAIT);
  }

  private HttpResponse<String> driverPatch(Driver d, String path, String body) throws Exception {
    return send(
        req(path)
            .header("user-context", userContext(d.user().getUserId()))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body)));
  }

  private HttpResponse<String> mechanicPatch(Mechanic m, String path) throws Exception {
    return send(
        req(path)
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  private HttpResponse<String> withdraw(Mechanic m, UUID bidId) throws Exception {
    return mechanicPatch(m, "/api/v1/bids/" + bidId + "/cancel");
  }

  private void expire(UUID sosId) {
    var sos = sosRequestRepository.findById(sosId).orElseThrow();
    sos.setBiddingExpiresAt(LocalDateTime.now().minusMinutes(1));
    sosRequestRepository.save(sos);
    sosService.expireOverduePendingRequests();
  }

  @Test
  void aCancelledRequest_leavesNoPendingBid() throws Exception {
    Driver d = newDriver();
    Mechanic a = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic b = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID bidA = placeBidOk(a, sosId);
    UUID bidB = placeBidOk(b, sosId);

    HttpResponse<String> cancelled = driverPatch(d, "/api/v1/sos/" + sosId + "/cancel", "");

    assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
    assertThat(statusOf(bidA)).isEqualTo(BidStatus.REJECTED);
    assertThat(statusOf(bidB)).isEqualTo(BidStatus.REJECTED);
    assertThat(bidsOn(sosId)).noneMatch(x -> x.getStatus() == BidStatus.PENDING);
  }

  @Test
  void anExpiredRequest_closesTheBids_andEachBidderIsTold() throws Exception {
    Driver d = newDriver();
    Mechanic a = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic b = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID bidA = placeBidOk(a, sosId);
    UUID bidB = placeBidOk(b, sosId);
    StompSession sessionA =
        stomp.connect(port, "mechanic-context", mechanicContext(a.getMechanicId()));
    StompSession sessionB =
        stomp.connect(port, "mechanic-context", mechanicContext(b.getMechanicId()));
    Tap framesA = subscribe(sessionA, UPDATES);
    Tap framesB = subscribe(sessionB, UPDATES);
    awaitSubscriptions(UPDATES, 2);

    expire(sosId);

    assertThat(sosStatus(d, sosId)).isEqualTo("EXPIRED");
    assertThat(statusOf(bidA)).isEqualTo(BidStatus.REJECTED);
    assertThat(statusOf(bidB)).isEqualTo(BidStatus.REJECTED);
    assertThat(awaitFrame(framesA, sosId, "SOS_EXPIRED")).isNotNull();
    assertThat(awaitFrame(framesB, sosId, "SOS_EXPIRED")).isNotNull();
    sessionA.disconnect();
    sessionB.disconnect();
  }

  @Test
  void aRebroadcast_lets_aMechanicWhoBidLastRoundBidAgain() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID oldBid = placeBidOk(m, sosId);
    expire(sosId);
    assertThat(sosStatus(d, sosId)).isEqualTo("EXPIRED");

    HttpResponse<String> rebroadcast =
        send(
            req("/api/v1/sos/" + sosId + "/rebroadcast")
                .header("user-context", userContext(d.user().getUserId()))
                .POST(HttpRequest.BodyPublishers.noBody()));
    assertThat(rebroadcast.statusCode()).as(rebroadcast.body()).isEqualTo(200);

    assertThat(bidSosRepository.findById(oldBid)).as("last round's bid is gone").isEmpty();
    UUID newBid = placeBidOk(m, sosId);
    assertThat(newBid).isNotEqualTo(oldBid);
    assertThat(bidsOn(sosId)).as("one row per mechanic and ticket").hasSize(1);
  }

  @Test
  void aWithdrawnBid_canBePlacedAgainOnce_butNotTwiceAtTheSameTime() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID first = placeBidOk(m, sosId);
    assertThat(withdraw(m, first).statusCode()).isEqualTo(200);

    UUID second = placeBidOk(m, sosId);

    assertThat(second).isNotEqualTo(first);
    assertThat(bidsOn(sosId)).as("the withdrawn row was replaced").hasSize(1);
    HttpResponse<String> again = placeBid(m, sosId);
    assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
    assertThat(withdraw(m, second).statusCode()).isEqualTo(200);
    placeBidOk(m, sosId); // and the same again after a second withdrawal
  }

  @Test
  void aMechanicWhoReleasedTheJob_cannotBidOnItAgain() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    assignMechanic(d, m, sosId);
    HttpResponse<String> released = mechanicPatch(m, "/api/v1/sos/" + sosId + "/mechanic-release");
    assertThat(released.statusCode()).as(released.body()).isEqualTo(200);

    HttpResponse<String> again = placeBid(m, sosId);

    assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
    assertThat(again.body()).contains("released this job");
  }

  @Test
  void aBidTheCustomerRejects_isReportedToThatMechanic() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID bidId = placeBidOk(m, sosId);
    StompSession session =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap frames = subscribe(session, UPDATES);
    awaitSubscriptions(UPDATES, 1);

    HttpResponse<String> rejected =
        driverPatch(
            d,
            "/api/v1/bids/" + bidId + "/status",
            "{\"userId\":\"" + d.user().getUserId() + "\",\"status\":\"REJECTED\"}");

    assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
    JsonNode frame = awaitFrame(frames, sosId, "BID_REJECTED");
    assertThat(frame.get("bidId").asString()).isEqualTo(bidId.toString());
    assertThat(statusOf(bidId)).isEqualTo(BidStatus.REJECTED);
    session.disconnect();
  }
}
