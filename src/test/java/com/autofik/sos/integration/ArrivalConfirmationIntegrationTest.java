package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import com.autofik.sos.service.impl.SosServiceImpl;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;
import tools.jackson.databind.JsonNode;

/**
 * The mechanic says they have arrived and the customer is asked: confirm, say "not here", or say
 * nothing and be confirmed automatically. The sweep is called directly; its timer is not tested.
 */
class ArrivalConfirmationIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";
  private static final String UPDATES = "/user/queue/sos-updates";
  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired private SosServiceImpl sosService;
  @Autowired private SosStatusHistoryRepository historyRepository;

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

  private UUID onTheWay(Driver d, Mechanic m) throws Exception {
    UUID sosId = createSosOk(d);
    assignMechanic(d, m, sosId);
    HttpResponse<String> r =
        send(
            req("/api/v1/sos/" + sosId + "/status?toStatus=" + SosStatus.ON_THE_WAY)
                .header("mechanic-context", mechanicContext(m.getMechanicId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return sosId;
  }

  @Test
  void aProposal_asksTheCustomer_andLeavesTheTicketOnTheWay() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = onTheWay(d, m);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap frames = subscribe(customer, UPDATES);
    awaitSubscriptions(UPDATES, 1);

    UUID historyId = proposeArrivalOk(m, sosId);

    JsonNode asked = awaitFrame(frames, sosId, "ARRIVAL_REQUESTED");
    assertThat(asked.get("historyId").asString()).isEqualTo(historyId.toString());
    assertThat(asked.get("approveBy").asString()).isNotBlank();
    assertThat(sosStatus(d, sosId)).isEqualTo("ON_THE_WAY");
    // A customer who missed the frame finds the request on the ticket.
    HttpResponse<String> ticket =
        send(
            req("/api/v1/sos/" + sosId)
                .header("user-context", userContext(d.user().getUserId()))
                .GET());
    assertThat(parse(ticket.body()).get("pendingCompletionHistoryId").asString())
        .isEqualTo(historyId.toString());
    // A second tap returns the same request instead of asking again.
    assertThat(proposeArrivalOk(m, sosId)).isEqualTo(historyId);
    customer.disconnect();
  }

  @Test
  void whenTheCustomerSaysNotHere_theTicketStaysOnTheWay_andTheMechanicIsTold() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = onTheWay(d, m);
    StompSession mechanicSession =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap frames = subscribe(mechanicSession, UPDATES);
    awaitSubscriptions(UPDATES, 1);
    UUID historyId = proposeArrivalOk(m, sosId);

    HttpResponse<String> answer = answerRequest(d, historyId, "REJECTED");

    assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
    awaitFrame(frames, sosId, "ARRIVAL_REJECTED");
    assertThat(sosStatus(d, sosId)).isEqualTo("ON_THE_WAY");
    assertThat(proposeArrivalOk(m, sosId)).as("the mechanic may ask again").isNotEqualTo(historyId);
    mechanicSession.disconnect();
  }

  @Test
  void anUnansweredRequest_isConfirmedAfterTheWindow_andTheCustomerIsTold() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = onTheWay(d, m);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap frames = subscribe(customer, UPDATES);
    awaitSubscriptions(UPDATES, 1);
    UUID historyId = proposeArrivalOk(m, sosId);

    sosService.approveOverdueCompletions();
    assertThat(sosStatus(d, sosId)).as("inside the window").isEqualTo("ON_THE_WAY");

    SosStatusHistory h = historyRepository.findById(historyId).orElseThrow();
    h.setProposedAt(LocalDateTime.now().minusMinutes(2));
    historyRepository.save(h);
    sosService.approveOverdueCompletions();

    awaitFrame(frames, sosId, "ARRIVAL_AUTO_CONFIRMED");
    assertThat(sosStatus(d, sosId)).isEqualTo("ARRIVED");
    customer.disconnect();
  }

  @Test
  void theOldWayOfMarkingArrived_is400_pointingToTheNewCall() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = onTheWay(d, m);

    HttpResponse<String> r =
        send(
            req("/api/v1/sos/" + sosId + "/status?toStatus=" + SosStatus.ARRIVED)
                .header("mechanic-context", mechanicContext(m.getMechanicId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));

    assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
    assertThat(r.body()).contains("/arrival");
    assertThat(sosStatus(d, sosId)).isEqualTo("ON_THE_WAY");
  }

  @Test
  void aMechanicWhoIsNotAssigned_cannotAskForTheCustomersConfirmation() throws Exception {
    Driver d = newDriver();
    Mechanic assigned = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic outsider = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = onTheWay(d, assigned);

    assertThat(proposeArrival(outsider, sosId).statusCode()).isEqualTo(403);
    assertThat(sosStatus(d, sosId)).isEqualTo("ON_THE_WAY");
  }

  /**
   * The road-distance call to Google runs before the ticket lock, so a customer cancel during a
   * slow call does not wait for it; the arrival, checked again under the lock, is then refused.
   */
  @Test
  void aCancelDuringTheMapsCall_doesNotWait_andTheArrivalIsThenRefused() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = onTheWay(d, m);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              release.await(30, TimeUnit.SECONDS);
              return null; // no route: the straight-line fallback, 0 m here
            });

    CompletableFuture<HttpResponse<String>> arrival =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return proposeArrival(m, sosId);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    try {
      assertThat(entered.await(10, TimeUnit.SECONDS)).as("the arrival reached Google").isTrue();

      CompletableFuture<HttpResponse<String>> cancel =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return send(
                      req("/api/v1/sos/" + sosId + "/cancel")
                          .header("user-context", userContext(d.user().getUserId()))
                          .method("PATCH", HttpRequest.BodyPublishers.noBody()));
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      HttpResponse<String> cancelled = cancel.get(2, TimeUnit.SECONDS);
      assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
    } finally {
      release.countDown();
    }

    HttpResponse<String> refused = arrival.get(30, TimeUnit.SECONDS);
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
    assertThat(sosStatus(d, sosId)).isEqualTo("CANCELLED");
    assertThat(
            historyRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
                sosId, ApprovedStatus.PENDING_APPROVAL))
        .isEmpty();
  }
}
