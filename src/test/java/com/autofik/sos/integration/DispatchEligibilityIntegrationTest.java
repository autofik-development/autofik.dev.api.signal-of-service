package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.repository.BidSosRepository;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompSession;
import tools.jackson.databind.JsonNode;

/**
 * Who is offered a job and who may bid on it: a mechanic already on a live job, one whose last
 * location is old, one with the wrong skill, and one a long drive away are all left out, with the
 * same rule applied to the offer and to the bid.
 */
class DispatchEligibilityIntegrationTest extends FlowTestSupport {

  // The point createBody() sends; FlowTestSupport stubs Medusa to resolve every group to TOW.
  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";
  private static final String OFFERS = "/user/queue/sos-new-request";
  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired private BidSosRepository bidSosRepository;
  @Autowired private JdbcTemplate jdbc;

  private StompSession connect(Mechanic m) throws Exception {
    return stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
  }

  /** True when an offer for this ticket arrives on the tap within the wait. */
  private static boolean offered(Tap tap, UUID sosId, Duration wait) throws Exception {
    long deadline = System.currentTimeMillis() + wait.toMillis();
    while (System.currentTimeMillis() < deadline) {
      String frame = tap.next(Duration.ofMillis(deadline - System.currentTimeMillis()));
      if (frame == null) return false;
      JsonNode n = parse(frame);
      if (n.has("sosId") && sosId.toString().equals(n.get("sosId").asString())) return true;
    }
    return false;
  }

  private HttpResponse<String> cancel(Driver d, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/cancel")
            .header("user-context", userContext(d.user().getUserId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  @Test
  void aMechanicOnALiveJob_isNotOfferedAnotherOne_andCannotBid_untilTheJobEnds() throws Exception {
    Driver first = newDriver();
    Mechanic busy = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic free = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID firstSos = createSosOk(first);
    assignMechanic(first, busy, firstSos);

    StompSession busySession = connect(busy);
    StompSession freeSession = connect(free);
    Tap busyOffers = subscribe(busySession, OFFERS);
    Tap freeOffers = subscribe(freeSession, OFFERS);
    awaitSubscriptions(OFFERS, 2);

    Driver second = newDriver();
    UUID secondSos = createSosOk(second);

    assertThat(offered(freeOffers, secondSos, WAIT)).as("the free mechanic is offered").isTrue();
    assertThat(offered(busyOffers, secondSos, Duration.ofSeconds(1)))
        .as("a mechanic on a live job is not offered another")
        .isFalse();
    HttpResponse<String> refused = placeBid(busy, secondSos);
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(404);
    assertThat(
            bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(
                secondSos, busy.getMechanicId()))
        .as("no bid row for a mechanic who was never offered the job")
        .isFalse();
    assertThat(placeBid(free, secondSos).statusCode()).isEqualTo(201);

    assertThat(cancel(first, firstSos).statusCode()).isEqualTo(200);
    Driver third = newDriver();
    UUID thirdSos = createSosOk(third);
    assertThat(offered(busyOffers, thirdSos, WAIT))
        .as("offered again once the first job is over")
        .isTrue();
    assertThat(placeBid(busy, thirdSos).statusCode()).isEqualTo(201);

    busySession.disconnect();
    freeSession.disconnect();
  }

  @Test
  void aMechanicSeenLongAgo_isNotOffered_andCannotBid_butOneNeverRecordedIs() throws Exception {
    Mechanic fresh = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    fresh.setLocationUpdatedAt(LocalDateTime.now().minusMinutes(1));
    mechanicRepository.save(fresh);
    Mechanic stale = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    stale.setLocationUpdatedAt(LocalDateTime.now().minusMinutes(10));
    mechanicRepository.save(stale);
    Mechanic neverRecorded = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);

    StompSession freshSession = connect(fresh);
    StompSession staleSession = connect(stale);
    StompSession unknownSession = connect(neverRecorded);
    Tap freshOffers = subscribe(freshSession, OFFERS);
    Tap staleOffers = subscribe(staleSession, OFFERS);
    Tap unknownOffers = subscribe(unknownSession, OFFERS);
    awaitSubscriptions(OFFERS, 3);

    UUID sosId = createSosOk(newDriver());

    assertThat(offered(freshOffers, sosId, WAIT)).isTrue();
    assertThat(offered(unknownOffers, sosId, WAIT))
        .as("no recorded ping time counts as fresh")
        .isTrue();
    assertThat(offered(staleOffers, sosId, Duration.ofSeconds(1))).isFalse();
    assertThat(placeBid(stale, sosId).statusCode()).isEqualTo(404);
    assertThat(placeBid(fresh, sosId).statusCode()).isEqualTo(201);

    freshSession.disconnect();
    staleSession.disconnect();
    unknownSession.disconnect();
  }

  @Test
  void aMechanicWithTheWrongSkill_cannotBid_andNoRowIsWritten() throws Exception {
    Mechanic wrongSkill = skilledMechanic(SOS_LAT, SOS_LNG, "BATTERY");
    UUID sosId = createSosOk(newDriver());

    HttpResponse<String> refused = placeBid(wrongSkill, sosId);

    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(404);
    assertThat(
            bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(
                sosId, wrongSkill.getMechanicId()))
        .isFalse();
  }

  @Test
  void aMechanicAFarDriveAway_isNotOffered_andCannotBid_whileANearOneIs() throws Exception {
    // Only the nearest offer-max-mechanics get a road estimate, and the cap is only applied where
    // one exists. Mechanics left parked on this point by earlier tests would push the far one out
    // of that set and it would be offered unestimated, so take them all out of matching first.
    jdbc.update("update mechanic set current_h3_index = null");
    // Both are well inside the radius by straight line; only the road estimate tells them apart.
    Mechanic near = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic far = skilledMechanic(SOS_LAT, SOS_LNG + 0.0001, PROBLEM_TYPE);
    double farX = far.getCurrentLocation().getX();
    when(googleMapsService.getDistanceInfos(any(), any()))
        .thenAnswer(
            inv -> {
              List<Point> origins = inv.getArgument(0);
              return origins.stream().map(p -> roadEta(p.getX() == farX ? 45 : 5)).toList();
            });
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenAnswer(inv -> roadEta(((Point) inv.getArgument(0)).getX() == farX ? 45 : 5));

    StompSession nearSession = connect(near);
    StompSession farSession = connect(far);
    Tap nearOffers = subscribe(nearSession, OFFERS);
    Tap farOffers = subscribe(farSession, OFFERS);
    awaitSubscriptions(OFFERS, 2);

    UUID sosId = createSosOk(newDriver());

    assertThat(offered(nearOffers, sosId, WAIT)).isTrue();
    assertThat(offered(farOffers, sosId, Duration.ofSeconds(1)))
        .as("a 45 minute drive is over the 30 minute cap")
        .isFalse();
    HttpResponse<String> refused = placeBid(far, sosId);
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
    assertThat(
            bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(
                sosId, far.getMechanicId()))
        .isFalse();
    assertThat(placeBid(near, sosId).statusCode()).isEqualTo(201);

    nearSession.disconnect();
    farSession.disconnect();
  }

  private static DistanceInfo roadEta(int minutes) {
    return new DistanceInfo(minutes * 700L, minutes * 60L, minutes + " km", minutes + " mins");
  }
}
