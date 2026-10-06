package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.impl.SosServiceImpl;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;
import tools.jackson.databind.JsonNode;

/**
 * A mechanic who accepted a job and then stood still: asked once, released if they do not answer,
 * kept if they say they are coming. The sweep is called directly; its timer is not what is tested.
 */
class NoShowIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";
  private static final String UPDATES = "/user/queue/sos-updates";
  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired private SosServiceImpl sosService;
  @Autowired private BidSosRepository bidSosRepository;
  @Autowired private SosRequestRepository sosRequestRepository;

  /** The mechanic has not moved for ten minutes. */
  private void standStill(Mechanic m) {
    Mechanic fresh = mechanicRepository.findById(m.getMechanicId()).orElseThrow();
    fresh.setLocationMovedAt(LocalDateTime.now().minusMinutes(10));
    mechanicRepository.save(fresh);
  }

  private BidSos acceptedBid(UUID sosId) {
    return bidSosRepository
        .findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED)
        .orElseThrow();
  }

  /** Pretend the question was asked two minutes ago, so the time to answer has run out. */
  private void expireTheQuestion(UUID sosId) {
    BidSos bid = acceptedBid(sosId);
    bid.setNoShowPromptedAt(LocalDateTime.now().minusMinutes(2));
    bidSosRepository.save(bid);
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

  private HttpResponse<String> stillComing(Mechanic m, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/still-coming")
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  @Test
  void aMechanicWhoStandsStillAndDoesNotAnswer_isAskedThenReleased_andTheJobIsOfferedAgain()
      throws Exception {
    Driver d = newDriver();
    Mechanic stuck = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic other = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    assignMechanic(d, stuck, sosId);
    standStill(stuck);

    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    StompSession stuckSession =
        stomp.connect(port, "mechanic-context", mechanicContext(stuck.getMechanicId()));
    StompSession otherSession =
        stomp.connect(port, "mechanic-context", mechanicContext(other.getMechanicId()));
    Tap customerFrames = subscribe(customer, UPDATES);
    Tap stuckFrames = subscribe(stuckSession, UPDATES);
    Tap stuckOffers = subscribe(stuckSession, "/user/queue/sos-new-request");
    Tap otherOffers = subscribe(otherSession, "/user/queue/sos-new-request");
    awaitSubscriptions(UPDATES, 2);
    awaitSubscriptions("/user/queue/sos-new-request", 2);

    sosService.checkNoShowJobs();

    JsonNode question = awaitFrame(stuckFrames, sosId, "STILL_COMING_CHECK");
    assertThat(question.get("respondBySeconds").asInt()).isEqualTo(60);
    assertThat(acceptedBid(sosId).getNoShowPromptedAt()).as("the question is recorded").isNotNull();
    assertThat(sosStatus(d, sosId)).as("asked, not released").isEqualTo("ACCEPTED");

    expireTheQuestion(sosId);
    sosService.checkNoShowJobs();

    assertThat(sosStatus(d, sosId)).isEqualTo("PENDING");
    assertThat(awaitFrame(customerFrames, sosId, "MECHANIC_NO_SHOW").get("fromStatus").asString())
        .isEqualTo("ACCEPTED");
    assertThat(awaitFrame(stuckFrames, sosId, "JOB_RELEASED").get("reason").asString())
        .isEqualTo("NO_SHOW");
    assertThat(awaitFrame(otherOffers, sosId, "SOS_CREATED")).isNotNull();
    assertThat(stuckOffers.next(Duration.ofSeconds(1)))
        .as("the mechanic who was released is not offered it again")
        .isNull();
    assertThat(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .isEmpty();

    customer.disconnect();
    stuckSession.disconnect();
    otherSession.disconnect();
  }

  @Test
  void aMechanicWhoSaysTheyAreComing_keepsTheJob_andTheClockStartsAgain() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    assignMechanic(d, m, sosId);
    standStill(m);
    sosService.checkNoShowJobs();
    assertThat(acceptedBid(sosId).getNoShowPromptedAt()).isNotNull();

    HttpResponse<String> answered = stillComing(m, sosId);

    assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
    assertThat(acceptedBid(sosId).getNoShowPromptedAt()).as("question withdrawn").isNull();
    sosService.checkNoShowJobs();
    assertThat(sosStatus(d, sosId)).as("still theirs").isEqualTo("ACCEPTED");
    assertThat(acceptedBid(sosId).getNoShowPromptedAt())
        .as("not asked again straight away")
        .isNull();
  }

  @Test
  void aMovingMechanic_orSomeoneElse_cannotBeReleasedOrAnswerForThem() throws Exception {
    Driver d = newDriver();
    Mechanic moving = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic outsider = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    assignMechanic(d, moving, sosId);
    Mechanic fresh = mechanicRepository.findById(moving.getMechanicId()).orElseThrow();
    fresh.setLocationMovedAt(LocalDateTime.now().minusSeconds(30));
    mechanicRepository.save(fresh);

    sosService.checkNoShowJobs();

    assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");
    assertThat(acceptedBid(sosId).getNoShowPromptedAt()).isNull();
    assertThat(stillComing(outsider, sosId).statusCode()).isEqualTo(403);
  }

  /**
   * A mechanic standing at the breakdown while the customer is asked to confirm the arrival does
   * not move, and must not be asked "are you still coming?" or released for it.
   */
  @Test
  void aMechanicWaitingForTheArrivalAnswer_isNotReleasedBySweep() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    assignMechanic(d, m, sosId);
    assertThat(
            send(req("/api/v1/sos/" + sosId + "/status?toStatus=" + SosStatus.ON_THE_WAY)
                    .header("mechanic-context", mechanicContext(m.getMechanicId()))
                    .method("PATCH", HttpRequest.BodyPublishers.noBody()))
                .statusCode())
        .isEqualTo(200);
    standStill(m);
    sosService.checkNoShowJobs(); // asks
    expireTheQuestion(sosId);
    UUID arrivalId = proposeArrivalOk(m, sosId);

    sosService.checkNoShowJobs();

    assertThat(sosStatus(d, sosId)).isEqualTo("ON_THE_WAY");
    assertThat(acceptedBid(sosId).getMechanic().getMechanicId()).isEqualTo(m.getMechanicId());
    assertThat(answerRequest(d, arrivalId, "ACCEPTED").statusCode()).isEqualTo(200);
    assertThat(sosStatus(d, sosId)).isEqualTo("ARRIVED");
  }
}
