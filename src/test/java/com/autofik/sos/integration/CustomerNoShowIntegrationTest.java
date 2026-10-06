package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.repository.SosStatusHistoryRepository;
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
 * The mechanic is at the breakdown, the customer never comes, and after the wait the mechanic ends
 * the job. The wait is shortened by back-dating when ARRIVED took effect.
 */
class CustomerNoShowIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";
  private static final String UPDATES = "/user/queue/sos-updates";
  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired private SosStatusHistoryRepository historyRepository;

  private UUID arrivedJob(Driver d, Mechanic m) throws Exception {
    UUID sosId = createSosOk(d);
    assignMechanic(d, m, sosId);
    HttpResponse<String> r =
        send(
            req("/api/v1/sos/" + sosId + "/status?toStatus=" + SosStatus.ON_THE_WAY)
                .header("mechanic-context", mechanicContext(m.getMechanicId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    arrive(d, m, sosId);
    return sosId;
  }

  /** Pretend ARRIVED took effect this many minutes ago. */
  private void arrivedMinutesAgo(UUID sosId, long minutes) {
    for (SosStatusHistory h : historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId)) {
      if (h.getToStatus() == SosStatus.ARRIVED
          && h.getApprovedStatus() == ApprovedStatus.ACCEPTED) {
        h.setApproveAt(LocalDateTime.now().minusMinutes(minutes));
        historyRepository.save(h);
      }
    }
  }

  private HttpResponse<String> reportNoShow(Mechanic m, UUID sosId) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/customer-no-show")
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  @Test
  void afterTheWait_theMechanicEndsTheJob_isFreeAgain_andTheCustomerIsTold() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = arrivedJob(d, m);
    StompSession customer = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap frames = subscribe(customer, UPDATES);
    awaitSubscriptions(UPDATES, 1);
    arrivedMinutesAgo(sosId, 11);

    HttpResponse<String> r = reportNoShow(m, sosId);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(sosStatus(d, sosId)).isEqualTo("CANCELLED");
    long deadline = System.currentTimeMillis() + WAIT.toMillis();
    String type = null;
    while (System.currentTimeMillis() < deadline && !"CUSTOMER_NO_SHOW".equals(type)) {
      String frame = frames.next(Duration.ofMillis(deadline - System.currentTimeMillis()));
      if (frame == null) break;
      JsonNode n = parse(frame);
      type = n.has("type") ? n.get("type").asString() : null;
    }
    assertThat(type).as("the customer's frame").isEqualTo("CUSTOMER_NO_SHOW");
    assertThat(historyRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId))
        .anyMatch(
            h -> h.getFromStatus() == SosStatus.ARRIVED && h.getToStatus() == SosStatus.CANCELLED);

    // The mechanic is not tied to the dead job any more: they may bid on a new one.
    Driver next = newDriver();
    UUID another = createSosOk(next);
    assertThat(placeBid(m, another).statusCode()).isEqualTo(201);
    customer.disconnect();
  }

  @Test
  void beforeTheWaitIsOver_itIs409_andTheJobStaysArrived() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = arrivedJob(d, m);
    arrivedMinutesAgo(sosId, 4);

    HttpResponse<String> r = reportNoShow(m, sosId);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
    assertThat(r.body()).contains("more minute");
    assertThat(sosStatus(d, sosId)).isEqualTo("ARRIVED");
  }

  @Test
  void aMechanicWhoIsNotAssigned_isDenied() throws Exception {
    Driver d = newDriver();
    Mechanic assigned = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic outsider = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = arrivedJob(d, assigned);
    arrivedMinutesAgo(sosId, 30);

    assertThat(reportNoShow(outsider, sosId).statusCode()).isEqualTo(403);
    assertThat(sosStatus(d, sosId)).isEqualTo("ARRIVED");
  }

  @Test
  void ifTheCustomerCancelledFirst_theReportIsRefused() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = arrivedJob(d, m);
    arrivedMinutesAgo(sosId, 30);
    HttpResponse<String> cancel =
        send(
            req("/api/v1/sos/" + sosId + "/cancel")
                .header("user-context", userContext(d.user().getUserId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);

    assertThat(reportNoShow(m, sosId).statusCode()).isEqualTo(409);
  }
}
