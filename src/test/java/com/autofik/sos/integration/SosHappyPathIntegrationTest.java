package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;
import tools.jackson.databind.JsonNode;

/**
 * Happy paths only, checked at every edge the clients and the other services see: the REST answer,
 * the event on Kafka (topic, sosId key, payload) and the frame on the socket. The other flow tests
 * already cover creation, the location ping and the rules around each step; this class pins the
 * events a successful job emits, which nothing else asserted.
 */
class SosHappyPathIntegrationTest extends FlowTestSupport {

  // Where createBody() puts the breakdown (Phnom Penh); a mechanic parked here can be dispatched
  // and passes the arrival distance check.
  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW"; // what the Medusa stub resolves to
  private static final Duration WAIT = Duration.ofSeconds(30);

  // ── bids ───────────────────────────────────────────────────────────────────

  @Test
  void shouldPublishBidCreatedEvent_whenMechanicBidsOnAnOpenSos() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);

    try (KafkaConsumer<String, String> created = tap("sos.bid.created")) {
      HttpResponse<String> r = placeBid(m, sosId);

      assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
      JsonNode bid = parse(r.body());
      assertThat(bid.get("status").asString()).isEqualTo("PENDING");
      String bidId = bid.get("bidId").asString();

      JsonNode event = parse(awaitRecord(created, sosId.toString(), WAIT).value());
      assertThat(event.get("bidId").asString()).isEqualTo(bidId);
      assertThat(event.get("sosId").asString()).isEqualTo(sosId.toString());
      assertThat(event.get("mechanicId").asString()).isEqualTo(m.getMechanicId().toString());
      assertThat(event.get("price").decimalValue()).isEqualByComparingTo("25.00");
      assertThat(event.get("bidAt").asString()).isNotBlank();
    }
    assertThat(sosStatus(d, sosId)).as("a bid alone does not move the ticket").isEqualTo("PENDING");
  }

  @Test
  void shouldDeliverTheBidToTheCustomer_throughStomp_whenMechanicBids() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    StompSession driver = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    Tap updates = subscribe(driver, "/user/queue/sos-updates");
    awaitSubscriptions("/user/queue/sos-updates", 1);

    UUID bidId = placeBidOk(m, sosId);

    JsonNode frame = awaitTyped(updates, sosId, "BID_RECEIVED");
    assertThat(frame.get("bid").get("bidId").asString()).isEqualTo(bidId.toString());
    assertThat(frame.get("bid").get("status").asString()).isEqualTo("PENDING");
    driver.disconnect();
  }

  @Test
  void shouldAcceptBid_andRejectOtherBids_whenCustomerAcceptsBid() throws Exception {
    Driver d = newDriver();
    Mechanic winner = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    Mechanic loser = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    UUID winnerBid = placeBidOk(winner, sosId);
    UUID loserBid = placeBidOk(loser, sosId);

    try (KafkaConsumer<String, String> accepted = tap("sos.bid.accepted");
        KafkaConsumer<String, String> rejected = tap("sos.bid.rejected")) {
      acceptBidOk(d, winnerBid);

      assertThat(sosStatus(d, sosId)).isEqualTo("ACCEPTED");
      Map<String, String> statuses = bidStatuses(d, sosId);
      assertThat(statuses.get(winnerBid.toString())).isEqualTo("ACCEPTED");
      assertThat(statuses.get(loserBid.toString())).isEqualTo("REJECTED");

      JsonNode acceptedEvent = parse(awaitRecord(accepted, sosId.toString(), WAIT).value());
      assertThat(acceptedEvent.get("bidId").asString()).isEqualTo(winnerBid.toString());
      assertThat(acceptedEvent.get("mechanicId").asString())
          .isEqualTo(winner.getMechanicId().toString());
      assertThat(acceptedEvent.get("userId").asString()).isEqualTo(d.user().getUserId().toString());
      assertThat(acceptedEvent.get("latitude").asDouble()).isEqualTo(SOS_LAT);
      assertThat(acceptedEvent.get("longitude").asDouble()).isEqualTo(SOS_LNG);

      // The losers go out in one batch event, not one per bid.
      JsonNode rejectedEvent = parse(awaitRecord(rejected, sosId.toString(), WAIT).value());
      List<String> rejectedIds = new ArrayList<>();
      rejectedEvent.get("bidIds").forEach(id -> rejectedIds.add(id.asString()));
      assertThat(rejectedIds).containsExactly(loserBid.toString());
    }
  }

  // ── status lifecycle and completion ────────────────────────────────────────

  @Test
  void shouldPublishStatusUpdatedEvent_forEveryForwardTransition_untilCompleted() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);

    try (KafkaConsumer<String, String> statusTopic = tap("sos.status.updated")) {
      completeJob(d, m, sosId);

      List<JsonNode> events = awaitRecords(statusTopic, sosId.toString(), 4);
      List<String> transitions = new ArrayList<>();
      for (JsonNode e : events) {
        transitions.add(e.get("fromStatus").asString() + ">" + e.get("toStatus").asString());
        assertThat(e.get("sosId").asString()).isEqualTo(sosId.toString());
        assertThat(e.get("mechanicId").asString()).isEqualTo(m.getMechanicId().toString());
        assertThat(e.get("userId").asString()).isEqualTo(d.user().getUserId().toString());
        assertThat(e.get("changedAt").asString()).isNotBlank();
      }
      // PENDING>ACCEPTED is not on this topic: the accept goes out as sos.bid.accepted.
      assertThat(transitions)
          .containsExactly(
              "ACCEPTED>ON_THE_WAY",
              "ON_THE_WAY>ARRIVED",
              "ARRIVED>IN_PROGRESS",
              "IN_PROGRESS>COMPLETED");
    }
    assertThat(sosStatus(d, sosId)).isEqualTo("COMPLETED");
  }

  @Test
  void shouldSendSosStatusUpdate_throughStomp_whenStatusChanges_includingCompletion()
      throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    StompSession driver = stomp.connect(port, "user-context", userContext(d.user().getUserId()));
    StompSession mechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(m.getMechanicId()));
    Tap driverFrames = subscribe(driver, "/user/queue/sos-updates");
    Tap mechanicFrames = subscribe(mechanic, "/user/queue/sos-updates");
    awaitSubscriptions("/user/queue/sos-updates", 2);

    completeJob(d, m, sosId);

    // The driver is told about the steps the mechanic takes alone (ON_THE_WAY, IN_PROGRESS). The
    // steps the driver confirms themself (ARRIVED, COMPLETED) go to the mechanic's queue; the
    // driver gets the REST answer for those.
    assertThat(statusFramesUntil(driverFrames, sosId, "ARRIVED>IN_PROGRESS"))
        .containsExactly("ACCEPTED>ON_THE_WAY", "ARRIVED>IN_PROGRESS");
    assertThat(statusFramesUntil(mechanicFrames, sosId, "IN_PROGRESS>COMPLETED"))
        .containsExactly("ON_THE_WAY>ARRIVED", "IN_PROGRESS>COMPLETED");
    mechanic.disconnect();
    driver.disconnect();
  }

  // ── mechanic discovery ─────────────────────────────────────────────────────

  @Test
  void shouldOfferOnlyOpenSosRequests_whenMechanicSyncsNearbyRequests() throws Exception {
    Mechanic searching = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID open = createSosOk(newDriver());

    Driver cancelling = newDriver();
    UUID cancelled = createSosOk(cancelling);
    HttpResponse<String> cancel =
        send(
            req("/api/v1/sos/" + cancelled + "/cancel")
                .header("user-context", userContext(cancelling.user().getUserId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);

    Driver assigned = newDriver();
    UUID taken = createSosOk(assigned);
    assignMechanic(assigned, skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE), taken);

    Driver finished = newDriver();
    UUID completed = createSosOk(finished);
    completeJob(finished, skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE), completed);

    StompSession mechanic =
        stomp.connect(port, "mechanic-context", mechanicContext(searching.getMechanicId()));
    Tap offers = subscribe(mechanic, "/user/queue/sos-new-request");
    Tap results = subscribe(mechanic, "/user/queue/bid-results");
    awaitSubscriptions("/user/queue/sos-new-request", 1);
    awaitSubscriptions("/user/queue/bid-results", 1);
    Thread.sleep(300); // let both SUBSCRIBEs land before the SEND

    mechanic.send("/app/sos/open", Map.of("clientMessageId", "happy-sync-1"));

    JsonNode ack = parse(results.next(WAIT));
    assertThat(ack.get("type").asString()).isEqualTo("OPEN_SOS_SYNCED");
    assertThat(ack.get("clientMessageId").asString()).isEqualTo("happy-sync-1");
    // The shared database holds other tests' open tickets too: compare by id, not by count.
    Map<String, JsonNode> offered = new HashMap<>();
    for (String f = offers.next(Duration.ofSeconds(2));
        f != null;
        f = offers.next(Duration.ofSeconds(2))) {
      JsonNode n = parse(f);
      offered.put(n.get("sosId").asString(), n);
    }
    assertThat(offered).hasSize(ack.get("count").asInt());
    assertThat(offered).containsKey(open.toString());
    assertThat(offered.get(open.toString()).get("type").asString()).isEqualTo("SOS_CREATED");
    assertThat(offered.keySet())
        .doesNotContain(cancelled.toString(), taken.toString(), completed.toString());
    mechanic.disconnect();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Bid, accept, on the way, arrival confirmed, work started, completion approved. */
  private void completeJob(Driver d, Mechanic m, UUID sosId) throws Exception {
    assignMechanic(d, m, sosId);
    assertThat(updateStatus(m, sosId, "ON_THE_WAY").statusCode()).isEqualTo(200);
    arrive(d, m, sosId);
    HttpResponse<String> started = updateStatus(m, sosId, "IN_PROGRESS");
    assertThat(started.statusCode()).as(started.body()).isEqualTo(200);

    HttpResponse<String> proposed =
        send(
            req("/api/v1/sos/" + sosId + "/completion")
                .header("mechanic-context", mechanicContext(m.getMechanicId()))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"mechanicId\":\""
                            + m.getMechanicId()
                            + "\",\"toStatus\":\"COMPLETED\"}")));
    assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
    UUID historyId = UUID.fromString(parse(proposed.body()).get("id").asString());

    HttpResponse<String> approved = answerRequest(d, historyId, "ACCEPTED");
    assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
  }

  private HttpResponse<String> updateStatus(Mechanic m, UUID sosId, String to) throws Exception {
    return send(
        req("/api/v1/sos/" + sosId + "/status?toStatus=" + to)
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
  }

  private Map<String, String> bidStatuses(Driver d, UUID sosId) throws Exception {
    HttpResponse<String> r =
        send(
            req("/api/v1/bids/sos/" + sosId)
                .header("user-context", userContext(d.user().getUserId()))
                .GET());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    Map<String, String> out = new HashMap<>();
    for (JsonNode b : parse(r.body()))
      out.put(b.get("bidId").asString(), b.get("status").asString());
    return out;
  }

  /** Every record with this key, in partition order, until {@code count} arrived. */
  private List<JsonNode> awaitRecords(
      KafkaConsumer<String, String> consumer, String key, int count) {
    List<JsonNode> out = new ArrayList<>();
    long deadline = System.currentTimeMillis() + WAIT.toMillis();
    while (out.size() < count && System.currentTimeMillis() < deadline) {
      for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
        if (key.equals(r.key())) out.add(parse(r.value()));
      }
    }
    assertThat(out).as("records keyed %s", key).hasSize(count);
    return out;
  }

  /** Status frames for this ticket as "from>to", read until {@code last} arrives. */
  private static List<String> statusFramesUntil(Tap tap, UUID sosId, String last) throws Exception {
    List<String> seen = new ArrayList<>();
    while (!seen.contains(last)) {
      JsonNode e = awaitStatusFrame(tap, sosId);
      seen.add(e.get("fromStatus").asString() + ">" + e.get("toStatus").asString());
    }
    return seen;
  }

  /** The next status frame for this ticket; the queue also carries bid frames, skipped here. */
  private static JsonNode awaitStatusFrame(Tap tap, UUID sosId) throws Exception {
    long deadline = System.currentTimeMillis() + WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      String frame =
          tap.next(Duration.ofMillis(Math.max(1, deadline - System.currentTimeMillis())));
      if (frame == null) break;
      JsonNode n = parse(frame);
      if (n.has("toStatus") && n.has("sosId") && sosId.toString().equals(n.get("sosId").asString()))
        return n;
    }
    throw new AssertionError("no status frame for " + sosId + " within " + WAIT);
  }

  private static JsonNode awaitTyped(Tap tap, UUID sosId, String type) throws Exception {
    Set<String> skipped = new HashSet<>();
    long deadline = System.currentTimeMillis() + WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      String frame =
          tap.next(Duration.ofMillis(Math.max(1, deadline - System.currentTimeMillis())));
      if (frame == null) break;
      JsonNode n = parse(frame);
      if (n.has("type")
          && type.equals(n.get("type").asString())
          && frame.contains(sosId.toString())) return n;
      skipped.add(n.has("type") ? n.get("type").asString() : "untyped");
    }
    throw new AssertionError("no " + type + " frame for " + sosId + "; skipped " + skipped);
  }
}
