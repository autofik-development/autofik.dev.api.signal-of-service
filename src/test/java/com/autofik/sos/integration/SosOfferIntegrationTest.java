package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosOffer;
import com.autofik.sos.repository.SosOfferRepository;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * G12: the offer log. Who was offered a ticket, whether they bid, and that a mechanic who released
 * a ticket is not offered it again.
 */
class SosOfferIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";

  @Autowired private SosOfferRepository sosOfferRepository;

  private HttpResponse<String> mechanicPatch(Mechanic m, String path) throws Exception {
    return send(
        req(path)
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  private List<SosOffer> offersFor(UUID sosId) {
    return sosOfferRepository.findAll().stream().filter(o -> sosId.equals(o.getSosId())).toList();
  }

  private long rowsFor(UUID sosId, Mechanic m) {
    return offersFor(sosId).stream()
        .filter(o -> o.getMechanicId().equals(m.getMechanicId()))
        .count();
  }

  private SosOffer offerOf(UUID sosId, Mechanic m) {
    return offersFor(sosId).stream()
        .filter(o -> o.getMechanicId().equals(m.getMechanicId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no offer row for " + m.getMechanicId()));
  }

  @Test
  void everyOfferedMechanicGetsOneRow_andABidMarksOnlyTheBidder() throws Exception {
    Driver d = newDriver();
    Mechanic bidder = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic silent = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);

    assertThat(rowsFor(sosId, bidder)).isEqualTo(1);
    assertThat(rowsFor(sosId, silent)).isEqualTo(1);
    assertThat(offersFor(sosId)).allMatch(o -> o.getBidAt() == null);

    placeBidOk(bidder, sosId);

    assertThat(offerOf(sosId, bidder).getBidAt()).isNotNull();
    assertThat(offerOf(sosId, silent).getBidAt()).as("ignored: offered, never bid").isNull();
    assertThat(rowsFor(sosId, bidder)).isEqualTo(1);
  }

  @Test
  void aReleasedTicketIsNotOfferedToTheReleaserAgain_andNobodyCountsTwice() throws Exception {
    Driver d = newDriver();
    Mechanic releaser = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic other = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    acceptBidOk(d, placeBidOk(releaser, sosId));
    var firstBidAt = offerOf(sosId, releaser).getBidAt();

    assertThat(mechanicPatch(releaser, "/api/v1/sos/" + sosId + "/mechanic-release").statusCode())
        .isEqualTo(200);

    // The release fan-out offers the ticket again to "other" (already has a row): still one row
    // each.
    assertThat(rowsFor(sosId, releaser)).isEqualTo(1);
    assertThat(rowsFor(sosId, other)).isEqualTo(1);
    assertThat(offerOf(sosId, releaser).getBidAt()).isEqualTo(firstBidAt);
    assertThat(placeBid(releaser, sosId).statusCode()).isEqualTo(409);
  }

  @Test
  void aReBidKeepsTheFirstBidTime() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID first = placeBidOk(m, sosId);
    var firstBidAt = offerOf(sosId, m).getBidAt();
    assertThat(firstBidAt).isNotNull();

    assertThat(mechanicPatch(m, "/api/v1/bids/" + first + "/cancel").statusCode()).isEqualTo(200);
    placeBidOk(m, sosId);

    assertThat(offerOf(sosId, m).getBidAt()).isEqualTo(firstBidAt);
    assertThat(rowsFor(sosId, m)).isEqualTo(1);
  }
}
