package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;
import tools.jackson.databind.JsonNode;

/**
 * Bidding over a real socket: place, accept, withdraw and sync, each answered on the sender's own
 * queue with the request's {@code clientMessageId}, and every failure answered without closing the
 * socket.
 */
class BidSocketIntegrationTest extends FlowTestSupport {

  @org.springframework.beans.factory.annotation.Autowired
  private com.autofik.sos.repository.SosRequestRepository sosRequestRepository;

  private static final Duration WAIT = Duration.ofSeconds(10);

  private record Party(StompSession session, Tap results, Tap errors, Tap updates) {}

  private Party driverSocket(Driver d) throws Exception {
    return party("user-context", userContext(d.user().getUserId()), d.user().getUserId());
  }

  private Party mechanicSocket(Mechanic m) throws Exception {
    return party("mechanic-context", mechanicContext(m.getMechanicId()), m.getMechanicId());
  }

  private Party party(String header, String context, UUID id) throws Exception {
    StompSession s = stomp.connect(port, header, context);
    Party p =
        new Party(
            s,
            subscribe(s, "/user/queue/bid-results"),
            subscribe(s, "/user/queue/errors"),
            subscribe(s, "/user/queue/sos-updates"));
    awaitSubscriptions("/user/queue/bid-results", 1);
    Thread.sleep(300); // let all three SUBSCRIBEs land before the first SEND
    return p;
  }

  private static Map<String, Object> msg(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private UUID bidOnSocket(Party mechanic, UUID sosId, String clientMessageId) throws Exception {
    mechanic
        .session()
        .send("/app/bids", msg("sosId", sosId, "price", 25.5, "clientMessageId", clientMessageId));
    JsonNode reply = parse(mechanic.results().next(WAIT));
    assertThat(reply.get("type").asString()).isEqualTo("BID_PLACED");
    return UUID.fromString(reply.get("bid").get("bidId").asString());
  }

  @Test
  void mechanicBids_driverSeesIt_driverAccepts_winnerAndLoserAreTold() throws Exception {
    Driver d = newDriver();
    Mechanic winner = skilledMechanic(11.55, 104.92, "TOW");
    Mechanic loser = skilledMechanic(11.56, 104.93, "TOW");
    UUID sosId = createSosOk(d);
    Party driver = driverSocket(d);
    Party w = mechanicSocket(winner);
    Party l = mechanicSocket(loser);

    w.session().send("/app/bids", msg("sosId", sosId, "price", 20, "clientMessageId", "w-1"));
    JsonNode placed = parse(w.results().next(WAIT));
    assertThat(placed.get("type").asString()).isEqualTo("BID_PLACED");
    assertThat(placed.get("clientMessageId").asString()).isEqualTo("w-1");
    UUID winnerBid = UUID.fromString(placed.get("bid").get("bidId").asString());
    bidOnSocket(l, sosId, "l-1");

    assertThat(parse(driver.updates().next(WAIT)).get("type").asString()).isEqualTo("BID_RECEIVED");

    driver
        .session()
        .send(
            "/app/bids/decision",
            msg("bidId", winnerBid, "status", "ACCEPTED", "clientMessageId", "d-1"));

    JsonNode decided = parse(driver.results().next(WAIT));
    assertThat(decided.get("type").asString()).isEqualTo("BID_DECIDED");
    assertThat(decided.get("clientMessageId").asString()).isEqualTo("d-1");
    assertThat(decided.get("bid").get("status").asString()).isEqualTo("ACCEPTED");
    assertThat(parse(w.updates().next(WAIT)).get("type").asString()).isEqualTo("BID_ACCEPTED");
    assertThat(parse(l.updates().next(WAIT)).get("type").asString()).isEqualTo("BID_REJECTED");
  }

  @Test
  void mechanicWithdraws_driverIsTold() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(11.55, 104.92, "TOW");
    UUID sosId = createSosOk(d);
    Party driver = driverSocket(d);
    Party mech = mechanicSocket(m);
    UUID bidId = bidOnSocket(mech, sosId, "b-1");
    driver.updates().next(WAIT); // BID_RECEIVED

    mech.session().send("/app/bids/cancel", msg("bidId", bidId, "clientMessageId", "x-1"));

    JsonNode reply = parse(mech.results().next(WAIT));
    assertThat(reply.get("type").asString()).isEqualTo("BID_WITHDRAWN");
    assertThat(reply.get("clientMessageId").asString()).isEqualTo("x-1");
    assertThat(parse(driver.updates().next(WAIT)).get("type").asString())
        .isEqualTo("BID_CANCELLED");
  }

  @Test
  void failuresGetOneErrorFrame_withTheRestStatus_andTheSocketStaysOpen() throws Exception {
    Driver d = newDriver();
    Driver other = newDriver();
    Mechanic m = skilledMechanic(11.55, 104.92, "TOW");
    UUID sosId = createSosOk(d);
    Party driver = driverSocket(d);
    Party intruder = driverSocket(other);
    Party mech = mechanicSocket(m);
    UUID bidId = bidOnSocket(mech, sosId, "ok");

    // a driver cannot bid
    driver.session().send("/app/bids", msg("sosId", sosId, "price", 10, "clientMessageId", "e-1"));
    assertError(driver, 403, "e-1");
    // duplicate bid by the same mechanic
    mech.session().send("/app/bids", msg("sosId", sosId, "price", 10, "clientMessageId", "e-2"));
    assertError(mech, 409, "e-2");
    // bad price
    mech.session().send("/app/bids", msg("sosId", sosId, "price", 0, "clientMessageId", "e-3"));
    assertError(mech, 400, "e-3");
    // unknown SOS
    mech.session()
        .send("/app/bids", msg("sosId", UUID.randomUUID(), "price", 10, "clientMessageId", "e-4"));
    assertError(mech, 404, "e-4");
    // someone else's bid id
    intruder
        .session()
        .send(
            "/app/bids/decision",
            msg("bidId", bidId, "status", "ACCEPTED", "clientMessageId", "e-5"));
    assertError(intruder, 403, "e-5");

    assertThat(driver.session().isConnected()).isTrue();
    assertThat(mech.session().isConnected()).isTrue();
    assertThat(intruder.session().isConnected()).isTrue();
  }

  @Test
  void doubleAccept_givesOneWinner_theSecondGets409() throws Exception {
    Driver d = newDriver();
    Mechanic a = skilledMechanic(11.55, 104.92, "TOW");
    Mechanic b = skilledMechanic(11.56, 104.93, "TOW");
    UUID sosId = createSosOk(d);
    Party driver = driverSocket(d);
    UUID bidA = bidOnSocket(mechanicSocket(a), sosId, "a");
    UUID bidB = bidOnSocket(mechanicSocket(b), sosId, "b");

    driver
        .session()
        .send(
            "/app/bids/decision", msg("bidId", bidA, "status", "ACCEPTED", "clientMessageId", "1"));
    assertThat(parse(driver.results().next(WAIT)).get("type").asString()).isEqualTo("BID_DECIDED");
    driver
        .session()
        .send(
            "/app/bids/decision", msg("bidId", bidB, "status", "ACCEPTED", "clientMessageId", "2"));

    assertError(driver, 409, "2");
  }

  @Test
  void reconnectedClient_recoversItsBids_overTheSocket() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(11.55, 104.92, "TOW");
    UUID sosId = createSosOk(d);
    UUID bidId = bidOnSocket(mechanicSocket(m), sosId, "b-1");
    Party driver = driverSocket(d); // connects after the bid; nothing was replayed

    driver.session().send("/app/bids/sync", msg("sosId", sosId, "clientMessageId", "s-1"));

    JsonNode snapshot = parse(driver.results().next(WAIT));
    assertThat(snapshot.get("type").asString()).isEqualTo("BIDS_SNAPSHOT");
    assertThat(snapshot.get("clientMessageId").asString()).isEqualTo("s-1");
    assertThat(snapshot.get("bids")).hasSize(1);
    assertThat(snapshot.get("bids").get(0).get("bidId").asString()).isEqualTo(bidId.toString());
  }

  @Test
  void sendOutsideApp_isRefused_andNothingIsRelayedToSubscribers() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(11.55, 104.92, "TOW");
    UUID sosId = createSosOk(d);
    Party driver = driverSocket(d);
    Party mech = mechanicSocket(m);

    // forging a frame onto the driver's own queue name
    mech.session()
        .send("/user/" + d.user().getUserId() + "/queue/sos-updates", msg("type", "FORGED"));

    assertThat(driver.updates().next(Duration.ofSeconds(2))).as("forged frame delivered").isNull();
  }

  private void assertError(Party p, int status, String clientMessageId) throws Exception {
    String frame = p.errors().next(WAIT);
    assertThat(frame).as("error frame for %s", clientMessageId).isNotNull();
    JsonNode error = parse(frame);
    assertThat(error.get("status").asInt()).isEqualTo(status);
    assertThat(error.get("clientMessageId").asString()).isEqualTo(clientMessageId);
  }

  // ── reconnect sync: the offers still open for a mechanic who was not connected ──

  private static final String OFFERS = "/user/queue/sos-new-request";

  /** The mechanic connects only after the SOS was created, so the broadcast never reached them. */
  @Test
  void aMechanicWhoReconnects_asksForOpenOffers_andGetsTheMissedOneAndAnAck() throws Exception {
    Mechanic m = skilledMechanic(11.5564, 104.9282, "TOW");
    UUID sosId = createSosOk(newDriver()); // m is not connected: the frame goes nowhere

    Party mechanic = mechanicSocket(m);
    Tap offers = subscribe(mechanic.session(), OFFERS);
    awaitSubscriptions(OFFERS, 1);
    mechanic.session().send("/app/sos/open", msg("clientMessageId", "sync-1"));

    // Other tests' open tickets may be re-sent too (shared database); find this one.
    JsonNode offer = null;
    for (String frame = offers.next(WAIT); frame != null; frame = offers.next(WAIT)) {
      JsonNode n = parse(frame);
      if (sosId.toString().equals(n.get("sosId").asString())) {
        offer = n;
        break;
      }
    }
    assertThat(offer).as("the missed offer is re-sent").isNotNull();
    assertThat(offer.get("type").asString()).isEqualTo("SOS_CREATED");
    JsonNode ack = parse(mechanic.results().next(WAIT));
    assertThat(ack.get("type").asString()).isEqualTo("OPEN_SOS_SYNCED");
    assertThat(ack.get("clientMessageId").asString()).isEqualTo("sync-1");
    assertThat(ack.get("count").asInt()).isGreaterThanOrEqualTo(1);
    mechanic.session().disconnect();
  }

  @Test
  void theSync_leavesOutTicketsTheMechanicCouldNotBidOn() throws Exception {
    Mechanic wrongSkill = skilledMechanic(11.5564, 104.9282, "BATTERY");
    Mechanic alreadyBid = skilledMechanic(11.5564, 104.9282, "TOW");
    Mechanic farAway = skilledMechanic(13.0, 105.5, "TOW");
    Driver d = newDriver();
    UUID sosId = createSosOk(d);
    placeBidOk(alreadyBid, sosId);

    for (Mechanic m : List.of(wrongSkill, alreadyBid, farAway)) {
      Party p = mechanicSocket(m);
      Tap offers = subscribe(p.session(), OFFERS);
      awaitSubscriptions(OFFERS, 1);
      p.session().send("/app/sos/open", msg("clientMessageId", "sync-x"));

      JsonNode ack = parse(p.results().next(WAIT));
      assertThat(ack.get("type").asString()).isEqualTo("OPEN_SOS_SYNCED");
      JsonNode offered = null;
      for (String frame = offers.next(Duration.ofMillis(500));
          frame != null;
          frame = offers.next(Duration.ofMillis(500))) {
        JsonNode n = parse(frame);
        if (sosId.toString().equals(n.get("sosId").asString())) offered = n;
      }
      assertThat(offered)
          .as("this mechanic must not be re-sent a ticket they cannot bid on")
          .isNull();
      p.session().disconnect();
    }
  }

  @Test
  void aTicketWhoseBiddingWindowClosed_isNotResent() throws Exception {
    Mechanic m = skilledMechanic(11.5564, 104.9282, "TOW");
    UUID sosId = createSosOk(newDriver());
    var sos = sosRequestRepository.findById(sosId).orElseThrow();
    sos.setBiddingExpiresAt(java.time.LocalDateTime.now().minusMinutes(1));
    sosRequestRepository.save(sos);

    Party mechanic = mechanicSocket(m);
    Tap offers = subscribe(mechanic.session(), OFFERS);
    awaitSubscriptions(OFFERS, 1);
    mechanic.session().send("/app/sos/open", msg("clientMessageId", "sync-2"));

    // Other tests' tickets may still be open in the shared database and are rightly re-sent, so
    // check only that this one, whose window is closed, is not.
    parse(mechanic.results().next(WAIT));
    for (String frame = offers.next(Duration.ofMillis(500));
        frame != null;
        frame = offers.next(Duration.ofMillis(500))) {
      assertThat(parse(frame).get("sosId").asString()).isNotEqualTo(sosId.toString());
    }
    mechanic.session().disconnect();
  }

  @Test
  void aDriverSendingTheSync_getsA403ErrorFrame() throws Exception {
    Party driver = driverSocket(newDriver());

    driver.session().send("/app/sos/open", msg("clientMessageId", "sync-3"));

    JsonNode error = parse(driver.errors().next(WAIT));
    assertThat(error.get("status").asInt()).isEqualTo(403);
    assertThat(error.get("clientMessageId").asString()).isEqualTo("sync-3");
    driver.session().disconnect();
  }
}
