package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.external.DistanceInfo;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompSession;
import tools.jackson.databind.JsonNode;

/**
 * The same bidding scenarios run once over REST and once over the socket, each on its own fresh
 * ticket, and the two runs must be indistinguishable: same outcome and message for every step, same
 * rows left in the database, same frames received by the driver and by each mechanic.
 *
 * <p>The socket handlers call the same {@code BidService} methods as the REST controller, so this
 * guards the places where the two could still drift: identity handling, validation, error mapping,
 * JSON shape and date format, and the async hand-off for placing a bid.
 */
class BidParityIntegrationTest extends FlowTestSupport {

  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired private JdbcTemplate jdbc;

  // ── actors and the two ways of acting ──────────────────────────────────────

  /** Someone who can act: a driver or a mechanic, with a socket for the socket run. */
  private record Actor(
      String kind,
      UUID id,
      StompSession session,
      Tap results,
      Tap errors,
      Tap updates,
      ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending) {
    boolean mechanic() {
      return kind.startsWith("mech");
    }
  }

  /** What one step looked like to the caller, with ids and timestamps normalized away. */
  private record Result(String step, String outcome, String detail) {}

  private interface Via {
    Result place(String step, Actor who, UUID sosId, String priceJson) throws Exception;

    Result decide(String step, Actor who, UUID bidId, String status) throws Exception;

    Result cancel(String step, Actor who, UUID bidId) throws Exception;

    Result list(String step, Actor who, UUID sosId) throws Exception;
  }

  private static final class Fixture {
    Actor driver, otherDriver, mech1, mech2;
    UUID sosId;
    Mechanic mechanic1, mechanic2;
    final List<Result> trace = new ArrayList<>();
    final Map<UUID, String> bidNames = new LinkedHashMap<>();
    final List<Actor> extras = new ArrayList<>();
    final List<Mechanic> extraMechanics = new ArrayList<>();
    long googleBase;
  }

  private Actor actor(String kind, UUID id, String header, String context) throws Exception {
    StompSession s = stomp.connect(port, header, context);
    Actor a =
        new Actor(
            kind,
            id,
            s,
            subscribe(s, "/user/queue/bid-results"),
            subscribe(s, "/user/queue/errors"),
            subscribe(s, "/user/queue/sos-updates"),
            new ConcurrentHashMap<>());
    // One reader per socket hands every reply to whoever asked for that clientMessageId, so
    // several requests can be in flight on the same socket without stealing each other's frames.
    Thread pump =
        new Thread(
            () -> {
              try {
                while (s.isConnected()) {
                  for (Tap tap : List.of(a.results(), a.errors())) {
                    String frame = tap.next(Duration.ofMillis(10));
                    if (frame == null) continue;
                    JsonNode node = parse(frame);
                    String cid = node.path("clientMessageId").asString("");
                    a.pending().computeIfAbsent(cid, k -> new CompletableFuture<>()).complete(node);
                  }
                }
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
            },
            "parity-pump-" + kind);
    pump.setDaemon(true);
    pump.start();
    awaitSubscriptions("/user/queue/bid-results", 1);
    Thread.sleep(300);
    return a;
  }

  private Fixture fixture() throws Exception {
    Fixture f = new Fixture();
    Driver d = newDriver();
    Driver d2 = newDriver();
    f.mechanic1 = newMechanic(11.55, 104.92);
    f.mechanic2 = newMechanic(11.56, 104.93);
    f.sosId = createSosOk(d);
    f.driver =
        actor("driver", d.user().getUserId(), "user-context", userContext(d.user().getUserId()));
    f.otherDriver =
        actor(
            "otherDriver",
            d2.user().getUserId(),
            "user-context",
            userContext(d2.user().getUserId()));
    f.mech1 =
        actor(
            "mech1",
            f.mechanic1.getMechanicId(),
            "mechanic-context",
            mechanicContext(f.mechanic1.getMechanicId()));
    f.mech2 =
        actor(
            "mech2",
            f.mechanic2.getMechanicId(),
            "mechanic-context",
            mechanicContext(f.mechanic2.getMechanicId()));
    f.googleBase = googleCalls();
    return f;
  }

  private long googleCalls() {
    return Mockito.mockingDetails(googleMapsService).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("getDistanceInfo"))
        .count();
  }

  /** More mechanics for the races, each with its own socket. */
  private List<Actor> addMechanics(Fixture f, int n) throws Exception {
    List<Actor> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Mechanic m = newMechanic(11.5 + i * 0.001, 104.9 + i * 0.001);
      Actor a =
          actor(
              "mechExtra" + i,
              m.getMechanicId(),
              "mechanic-context",
              mechanicContext(m.getMechanicId()));
      f.extras.add(a);
      f.extraMechanics.add(m);
      out.add(a);
    }
    return out;
  }

  // ── REST ───────────────────────────────────────────────────────────────────

  private final Via rest =
      new Via() {
        private HttpRequest.Builder as(HttpRequest.Builder b, Actor who) {
          return b.header(
              who.mechanic() ? "mechanic-context" : "user-context",
              who.mechanic() ? mechanicContext(who.id()) : userContext(who.id()));
        }

        private Result result(String step, HttpResponse<String> r) {
          if (r.statusCode() >= 200 && r.statusCode() < 300) {
            return new Result(step, "OK", normalizeBid(parse(r.body())));
          }
          JsonNode body = parse(r.body());
          return new Result(
              step,
              "ERR " + r.statusCode(),
              errorText(r.statusCode(), body.get("message").asString()));
        }

        @Override
        public Result place(String step, Actor who, UUID sosId, String priceJson) throws Exception {
          String body =
              "{\"mechanicId\":\""
                  + who.id()
                  + "\",\"sosId\":\""
                  + sosId
                  + "\""
                  + (priceJson == null ? "" : ",\"price\":" + priceJson)
                  + "}";
          return result(
              step,
              send(
                  as(
                      req("/api/v1/bids")
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofString(body)),
                      who)));
        }

        @Override
        public Result decide(String step, Actor who, UUID bidId, String status) throws Exception {
          String body = "{\"userId\":\"" + who.id() + "\",\"status\":\"" + status + "\"}";
          return result(
              step,
              send(
                  as(
                      req("/api/v1/bids/" + bidId + "/status")
                          .header("Content-Type", "application/json")
                          .method("PATCH", HttpRequest.BodyPublishers.ofString(body)),
                      who)));
        }

        @Override
        public Result cancel(String step, Actor who, UUID bidId) throws Exception {
          return result(
              step,
              send(
                  as(
                      req("/api/v1/bids/" + bidId + "/cancel")
                          .method("PATCH", HttpRequest.BodyPublishers.noBody()),
                      who)));
        }

        @Override
        public Result list(String step, Actor who, UUID sosId) throws Exception {
          HttpResponse<String> r = send(as(req("/api/v1/bids/sos/" + sosId).GET(), who));
          if (r.statusCode() != 200) return new Result(step, "ERR " + r.statusCode(), "");
          return new Result(step, "OK", normalizeList(parse(r.body())));
        }
      };

  // ── socket ─────────────────────────────────────────────────────────────────

  private final Via socket =
      new Via() {
        private Result await(String step, Actor who, String cid) throws Exception {
          JsonNode f;
          try {
            f =
                who.pending()
                    .computeIfAbsent(cid, k -> new CompletableFuture<>())
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
          } catch (java.util.concurrent.TimeoutException e) {
            return new Result(step, "NO REPLY", "");
          }
          if (f.has("status") && f.has("error")) {
            return new Result(
                step,
                "ERR " + f.get("status").asInt(),
                errorText(f.get("status").asInt(), f.get("error").asString()));
          }
          if (f.has("bids")) return new Result(step, "OK", normalizeList(f.get("bids")));
          return new Result(step, "OK", normalizeBid(f.get("bid")));
        }

        private Result sendAndAwait(
            String step, Actor who, String destination, Map<String, Object> body) throws Exception {
          String cid = UUID.randomUUID().toString();
          body.put("clientMessageId", cid);
          // Only the client-side SEND is serialised (the test client's socket is not thread-safe);
          // the server still handles the frames concurrently.
          synchronized (who.session()) {
            who.session().send(destination, body);
          }
          return await(step, who, cid);
        }

        @Override
        public Result place(String step, Actor who, UUID sosId, String priceJson) throws Exception {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("sosId", sosId);
          if (priceJson != null) m.put("price", JSON.readTree(priceJson));
          return sendAndAwait(step, who, "/app/bids", m);
        }

        @Override
        public Result decide(String step, Actor who, UUID bidId, String status) throws Exception {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("bidId", bidId);
          m.put("status", status);
          return sendAndAwait(step, who, "/app/bids/decision", m);
        }

        @Override
        public Result cancel(String step, Actor who, UUID bidId) throws Exception {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("bidId", bidId);
          return sendAndAwait(step, who, "/app/bids/cancel", m);
        }

        @Override
        public Result list(String step, Actor who, UUID sosId) throws Exception {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("sosId", sosId);
          return sendAndAwait(step, who, "/app/bids/sync", m);
        }
      };

  // ── normalization: keep what a client can see, drop ids and clocks ─────────

  private static String shape(String text) {
    return text == null ? "null" : text.replaceAll("[0-9]", "#");
  }

  /** The bid as a client sees it, minus ids and times; the date format is kept as a shape. */
  private static String normalizeBid(JsonNode bid) {
    StringBuilder sb = new StringBuilder();
    sb.append("status=").append(bid.path("status").asString("?"));
    sb.append(" price=")
        .append(
            bid.path("price").isNull()
                ? "null"
                : bid.get("price").decimalValue().stripTrailingZeros().toPlainString());
    sb.append(" bidAt=").append(shape(bid.path("bidAt").asString(null)));
    sb.append(" responded=")
        .append(
            bid.path("respondedAt").isNull()
                ? "no"
                : shape(bid.path("respondedAt").asString(null)));
    sb.append(" est=")
        .append(bid.path("estimatedDistanceM"))
        .append('/')
        .append(bid.path("estimatedDistanceText"))
        .append('/')
        .append(bid.path("estimatedDurationS"))
        .append('/')
        .append(bid.path("estimatedDurationText"));
    List<String> fields = new ArrayList<>();
    bid.path("mechanic").propertyNames().forEach(fields::add);
    sb.append(" mechanicFields=").append(fields);
    List<String> top = new ArrayList<>();
    bid.propertyNames().forEach(top::add);
    sb.append(" fields=").append(top);
    return sb.toString();
  }

  private static String normalizeList(JsonNode bids) {
    List<String> out = new ArrayList<>();
    bids.forEach(b -> out.add(normalizeBid(b)));
    java.util.Collections.sort(out);
    return out.toString();
  }

  private static String errorText(int status, String message) {
    // A 400's wording is the framework's; clients branch on the status. Everything else must match.
    if (status == 400) return "-";
    return message == null
        ? ""
        : message.replaceAll(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>");
  }

  // ── what is left behind ────────────────────────────────────────────────────

  private List<String> snapshot(Fixture f) {
    List<String> s = new ArrayList<>();
    s.add(
        "sos="
            + jdbc.queryForObject(
                "select status from sos_request where sos_id = ?", String.class, f.sosId));
    jdbc.queryForList("select status, price from bid_sos where sos_id = ? order by price", f.sosId)
        .forEach(
            r ->
                s.add(
                    "bid "
                        + r.get("status")
                        + " "
                        + ((java.math.BigDecimal) r.get("price"))
                            .stripTrailingZeros()
                            .toPlainString()));
    s.add(
        "history="
            + jdbc.queryForObject(
                "select count(*) from sos_status_history where sos_id = ?", Long.class, f.sosId));
    return s;
  }

  /** The kinds of frame each party received on /user/queue/sos-updates, in any order. */
  private Map<String, List<String>> frames(Fixture f) throws Exception {
    Thread.sleep(1500);
    Map<String, List<String>> out = new LinkedHashMap<>();
    List<Actor> everyone = new ArrayList<>(List.of(f.driver, f.otherDriver, f.mech1, f.mech2));
    everyone.addAll(f.extras);
    for (Actor a : everyone) {
      List<String> types = new ArrayList<>();
      String frame;
      while ((frame = a.updates().next(Duration.ofMillis(100))) != null) {
        types.add(parse(frame).path("type").asString("(no type)"));
      }
      java.util.Collections.sort(types);
      out.put(a.kind(), types);
    }
    return out;
  }

  private record Outcome(
      List<Result> trace, List<String> snapshot, Map<String, List<String>> frames) {}

  private Outcome run(Via via, Consumer3 scenario) throws Exception {
    Fixture f = fixture();
    try {
      scenario.accept(via, f);
      return new Outcome(f.trace, snapshot(f), frames(f));
    } finally {
      for (Actor a : List.of(f.driver, f.otherDriver, f.mech1, f.mech2)) a.session().disconnect();
      for (Actor a : f.extras) a.session().disconnect();
    }
  }

  @FunctionalInterface
  private interface Consumer3 {
    void accept(Via via, Fixture f) throws Exception;
  }

  private void assertParity(Consumer3 scenario) throws Exception {
    Outcome overRest = run(rest, scenario);
    Outcome overSocket = run(socket, scenario);
    assertThat(overSocket.trace()).as("steps, socket against REST").isEqualTo(overRest.trace());
    assertThat(overSocket.snapshot())
        .as("rows left in the database")
        .isEqualTo(overRest.snapshot());
    assertThat(overSocket.frames()).as("frames each party received").isEqualTo(overRest.frames());
    assertThat(overRest.trace()).as("REST run produced steps").isNotEmpty();
    assertThat(overRest.trace())
        .as("no step went unanswered")
        .noneMatch(r -> r.outcome().equals("NO REPLY"));
  }

  /** Finds a bid id in the database, since the normalized result hides ids. */
  private UUID bidOf(Fixture f, Mechanic m) {
    return jdbc.queryForObject(
        "select bid_id from bid_sos where sos_id = ? and mechanic_id = ?",
        UUID.class,
        f.sosId,
        m.getMechanicId());
  }

  // ── scenarios ──────────────────────────────────────────────────────────────

  @Test
  void placeTwoBids_driverAcceptsOne_theOtherIsRejected() throws Exception {
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("m1 bids 20", f.mech1, f.sosId, "20"));
          f.trace.add(v.place("m2 bids 30.5", f.mech2, f.sosId, "30.5"));
          f.trace.add(v.list("driver lists", f.driver, f.sosId));
          f.trace.add(v.list("m2 lists (own bid only)", f.mech2, f.sosId));
          f.trace.add(v.decide("driver accepts m1", f.driver, bidOf(f, f.mechanic1), "ACCEPTED"));
          f.trace.add(v.list("driver lists after", f.driver, f.sosId));
        });
  }

  @Test
  void driverRejectsABid_thenItCannotBeWithdrawn() throws Exception {
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("m1 bids", f.mech1, f.sosId, "15"));
          f.trace.add(v.decide("driver rejects", f.driver, bidOf(f, f.mechanic1), "REJECTED"));
          f.trace.add(v.cancel("m1 withdraws a rejected bid", f.mech1, bidOf(f, f.mechanic1)));
          f.trace.add(
              v.decide("driver rejects again", f.driver, bidOf(f, f.mechanic1), "REJECTED"));
        });
  }

  @Test
  void withdraw_thenTheDriverCannotAcceptIt() throws Exception {
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("m1 bids", f.mech1, f.sosId, "22"));
          f.trace.add(v.cancel("m1 withdraws", f.mech1, bidOf(f, f.mechanic1)));
          f.trace.add(
              v.decide(
                  "driver accepts the withdrawn bid", f.driver, bidOf(f, f.mechanic1), "ACCEPTED"));
          f.trace.add(v.cancel("m1 withdraws twice", f.mech1, bidOf(f, f.mechanic1)));
        });
  }

  @Test
  void duplicateBid_isRefused() throws Exception {
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("m1 bids", f.mech1, f.sosId, "20"));
          f.trace.add(v.place("m1 bids again", f.mech1, f.sosId, "10"));
        });
  }

  @Test
  void doubleAccept_givesOneWinner() throws Exception {
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("m1 bids", f.mech1, f.sosId, "20"));
          f.trace.add(v.place("m2 bids", f.mech2, f.sosId, "25"));
          f.trace.add(v.decide("accept m1", f.driver, bidOf(f, f.mechanic1), "ACCEPTED"));
          f.trace.add(v.decide("accept m2 as well", f.driver, bidOf(f, f.mechanic2), "ACCEPTED"));
          f.trace.add(v.place("late bid on an accepted ticket", f.mech2, f.sosId, "5"));
        });
  }

  @Test
  void badPrices_andUnknownIds_areRefusedTheSameWay() throws Exception {
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("price 0", f.mech1, f.sosId, "0"));
          f.trace.add(v.place("price -5", f.mech1, f.sosId, "-5"));
          f.trace.add(v.place("price 0.009", f.mech1, f.sosId, "0.009"));
          f.trace.add(v.place("price missing", f.mech1, f.sosId, null));
          f.trace.add(v.place("price not a number", f.mech1, f.sosId, "\"abc\""));
          f.trace.add(v.place("unknown ticket", f.mech1, UUID.randomUUID(), "10"));
          f.trace.add(v.decide("unknown bid decided", f.driver, UUID.randomUUID(), "ACCEPTED"));
          f.trace.add(v.decide("status that does not exist", f.driver, UUID.randomUUID(), "NOPE"));
          f.trace.add(v.cancel("unknown bid withdrawn", f.mech1, UUID.randomUUID()));
          f.trace.add(v.place("valid after all that", f.mech1, f.sosId, "0.01"));
        });
  }

  @Test
  void wrongRolesAndSomeoneElsesBid_areForbiddenTheSameWay() throws Exception {
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("a driver bids", f.driver, f.sosId, "10"));
          f.trace.add(v.place("m1 bids", f.mech1, f.sosId, "20"));
          UUID bid = bidOf(f, f.mechanic1);
          f.trace.add(v.decide("a mechanic decides", f.mech2, bid, "ACCEPTED"));
          f.trace.add(v.decide("another driver decides", f.otherDriver, bid, "ACCEPTED"));
          f.trace.add(v.cancel("a driver withdraws", f.driver, bid));
          f.trace.add(v.cancel("another mechanic withdraws", f.mech2, bid));
          f.trace.add(v.decide("CANCELLED via decide", f.driver, bid, "CANCELLED"));
        });
  }

  @Test
  void ticketThatCannotTakeBids_refusesThemTheSameWay() throws Exception {
    assertParity(
        (v, f) -> {
          jdbc.update(
              "update mechanic set available_status = false where id = ?",
              f.mechanic2.getMechanicId());
          f.trace.add(v.place("unavailable mechanic bids", f.mech2, f.sosId, "10"));
          jdbc.update(
              "update sos_request set bidding_expires_at = now() - interval '1 minute' where sos_id = ?",
              f.sosId);
          f.trace.add(v.place("bid after the window closed", f.mech1, f.sosId, "10"));
          jdbc.update("update sos_request set status = 'CANCELLED' where sos_id = ?", f.sosId);
          f.trace.add(v.place("bid on a cancelled ticket", f.mech1, f.sosId, "10"));
        });
  }

  /**
   * The database refuses the value: REST maps that to a 409 with a fixed message, the socket must
   * too.
   */
  @Test
  void aPriceTheDatabaseCannotStore_isMappedTheSameWay() throws Exception {
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("price too large for the column", f.mech1, f.sosId, "1000000000"));
          f.trace.add(v.place("valid after it", f.mech1, f.sosId, "10"));
        });
  }

  // ── the Google estimate ────────────────────────────────────────────────────

  private Result googleStep(Fixture f) {
    return new Result(
        "Google calls made by this run", "info", String.valueOf(googleCalls() - f.googleBase));
  }

  /**
   * The distance and ETA stored with a bid, and shown to the driver, are the same over both paths.
   */
  @Test
  void googleEstimate_isStoredAndShownTheSameWay() throws Exception {
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenReturn(new DistanceInfo(5230, 720, "5.2 km", "12 mins"));
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("m1 bids with an estimate", f.mech1, f.sosId, "20"));
          f.trace.add(v.list("driver sees the estimate", f.driver, f.sosId));
          f.trace.add(googleStep(f));
        });
  }

  /** Google gives no answer: the bid is still placed, with no estimate, over both paths. */
  @Test
  void googleWithNoAnswer_stillPlacesTheBidTheSameWay() throws Exception {
    when(googleMapsService.getDistanceInfo(any(), any())).thenReturn(null);
    assertParity(
        (v, f) -> {
          f.trace.add(v.place("m1 bids, Google has no answer", f.mech1, f.sosId, "20"));
          f.trace.add(v.list("driver lists", f.driver, f.sosId));
          f.trace.add(googleStep(f));
        });
  }

  // ── races: several requests at the same instant ────────────────────────────

  /** Starts every action at the same moment and returns their results. */
  private List<Result> race(List<Callable<Result>> actions) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(actions.size());
    CountDownLatch ready = new CountDownLatch(actions.size());
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Result>> futures = new ArrayList<>();
      for (Callable<Result> action : actions) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return action.call();
                }));
      }
      ready.await();
      go.countDown();
      List<Result> out = new ArrayList<>();
      for (Future<Result> fu : futures) out.add(fu.get(60, TimeUnit.SECONDS));
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  private interface RaceScenario {
    List<Result> run(Via via, Fixture f) throws Exception;
  }

  /** What must hold after any interleaving, whatever the order requests were handled in. */
  private void assertInvariants(Fixture f, String via) {
    String sos =
        jdbc.queryForObject(
            "select status from sos_request where sos_id = ?", String.class, f.sosId);
    long accepted =
        jdbc.queryForObject(
            "select count(*) from bid_sos where sos_id = ? and status = 'ACCEPTED'",
            Long.class,
            f.sosId);
    long pending =
        jdbc.queryForObject(
            "select count(*) from bid_sos where sos_id = ? and status = 'PENDING'",
            Long.class,
            f.sosId);
    long dup =
        jdbc.queryForObject(
            "select count(*) from (select mechanic_id from bid_sos where sos_id = ? group by mechanic_id having count(*) > 1) t",
            Long.class,
            f.sosId);
    assertThat(accepted).as("%s: at most one accepted bid", via).isLessThanOrEqualTo(1);
    assertThat(dup).as("%s: no mechanic has two bids", via).isZero();
    if (sos.equals("ACCEPTED")) {
      assertThat(accepted).as("%s: an accepted ticket has its accepted bid", via).isEqualTo(1);
      assertThat(pending).as("%s: no bid left pending on an accepted ticket", via).isZero();
    } else {
      assertThat(accepted)
          .as("%s: a ticket that is not accepted has no accepted bid", via)
          .isZero();
    }
  }

  private static List<String> outcomes(List<Result> results) {
    List<String> out = new ArrayList<>();
    for (Result r : results)
      out.add(r.outcome().equals("OK") ? "OK" : r.outcome() + ": " + r.detail());
    java.util.Collections.sort(out);
    return out;
  }

  private List<String> dbShape(Fixture f) {
    List<String> s = new ArrayList<>();
    s.add(
        "sos="
            + jdbc.queryForObject(
                "select status from sos_request where sos_id = ?", String.class, f.sosId));
    List<String> bids = new ArrayList<>();
    jdbc.queryForList("select status from bid_sos where sos_id = ?", f.sosId)
        .forEach(r -> bids.add((String) r.get("status")));
    java.util.Collections.sort(bids);
    s.add("bids=" + bids);
    s.add(
        "history="
            + jdbc.queryForObject(
                "select count(*) from sos_status_history where sos_id = ?", Long.class, f.sosId));
    return s;
  }

  private List<String> allFrames(Fixture f) throws Exception {
    List<String> all = new ArrayList<>();
    frames(f).values().forEach(all::addAll);
    java.util.Collections.sort(all);
    return all;
  }

  private record RaceRun(List<String> outcomes, List<String> db, List<String> frames) {}

  private RaceRun runRace(Via via, String name, RaceScenario scenario) throws Exception {
    Fixture f = fixture();
    try {
      List<Result> results = scenario.run(via, f);
      assertThat(results)
          .as("%s: no request went unanswered", name)
          .noneMatch(r -> r.outcome().equals("NO REPLY"));
      assertInvariants(f, name);
      return new RaceRun(outcomes(results), dbShape(f), allFrames(f));
    } finally {
      for (Actor a : List.of(f.driver, f.otherDriver, f.mech1, f.mech2)) a.session().disconnect();
      for (Actor a : f.extras) a.session().disconnect();
    }
  }

  /** Same race over REST and over the socket: the invariants hold on each, and the totals agree. */
  private void assertRaceParity(boolean compareTotals, RaceScenario scenario) throws Exception {
    // A race can get lucky once, so every scenario runs several rounds on each path.
    for (int round = 1; round <= RACE_ROUNDS; round++) {
      RaceRun overRest = runRace(rest, "REST round " + round, scenario);
      RaceRun overSocket = runRace(socket, "socket round " + round, scenario);
      if (compareTotals) {
        assertThat(overSocket.outcomes())
            .as("outcomes, socket against REST, round %d", round)
            .isEqualTo(overRest.outcomes());
        assertThat(overSocket.db())
            .as("rows left in the database, round %d", round)
            .isEqualTo(overRest.db());
        assertThat(overSocket.frames())
            .as("frames received in total, round %d", round)
            .isEqualTo(overRest.frames());
      }
    }
  }

  private static final int RACE_ROUNDS = 6;

  @Test
  void race_tenMechanicsBidAtOnce_allAreStored() throws Exception {
    assertRaceParity(
        true,
        (v, f) -> {
          List<Actor> mechs = addMechanics(f, 10);
          List<Callable<Result>> actions = new ArrayList<>();
          for (int i = 0; i < mechs.size(); i++) {
            Actor m = mechs.get(i);
            String price = String.valueOf(10 + i);
            actions.add(() -> v.place("bid " + price, m, f.sosId, price));
          }
          List<Result> results = race(actions);
          assertThat(results).allMatch(r -> r.outcome().equals("OK"));
          return results;
        });
  }

  /**
   * A double-tap: the same mechanic sends the same bid five times at once. Exactly one is stored.
   */
  @Test
  void race_sameMechanicDoubleTap_oneBidIsStored() throws Exception {
    assertRaceParity(
        true,
        (v, f) -> {
          List<Callable<Result>> actions = new ArrayList<>();
          for (int i = 0; i < 5; i++)
            actions.add(() -> v.place("double tap", f.mech1, f.sosId, "20"));
          List<Result> results = race(actions);
          assertThat(results.stream().filter(r -> r.outcome().equals("OK")).count()).isEqualTo(1);
          return results;
        });
  }

  /** The driver taps accept on two different bids at once: one wins, the other is refused. */
  @Test
  void race_driverAcceptsTwoBidsAtOnce_oneWins() throws Exception {
    assertRaceParity(
        true,
        (v, f) -> {
          v.place("m1 bids", f.mech1, f.sosId, "20");
          v.place("m2 bids", f.mech2, f.sosId, "25");
          UUID bidA = bidOf(f, f.mechanic1);
          UUID bidB = bidOf(f, f.mechanic2);
          List<Result> results =
              race(
                  List.of(
                      () -> v.decide("accept A", f.driver, bidA, "ACCEPTED"),
                      () -> v.decide("accept B", f.driver, bidB, "ACCEPTED")));
          assertThat(results.stream().filter(r -> r.outcome().equals("OK")).count()).isEqualTo(1);
          return results;
        });
  }

  /**
   * New bids arrive while the driver accepts: nothing may be left pending on the accepted ticket.
   */
  @Test
  void race_bidsArriveWhileTheDriverAccepts_noBidIsLeftPending() throws Exception {
    assertRaceParity(
        false,
        (v, f) -> {
          v.place("m1 bids", f.mech1, f.sosId, "20");
          UUID bidA = bidOf(f, f.mechanic1);
          List<Actor> late = addMechanics(f, 8);
          List<Callable<Result>> actions = new ArrayList<>();
          actions.add(() -> v.decide("accept A", f.driver, bidA, "ACCEPTED"));
          for (int i = 0; i < late.size(); i++) {
            Actor m = late.get(i);
            String price = String.valueOf(30 + i);
            actions.add(() -> v.place("late bid " + price, m, f.sosId, price));
          }
          List<Result> results = race(actions);
          assertThat(
                  results.stream()
                      .filter(r -> r.step().equals("accept A") && r.outcome().equals("OK"))
                      .count())
              .isEqualTo(1);
          // every bid that was answered OK is stored; every other one was refused with a 409
          long placedOk =
              results.stream()
                  .filter(r -> r.step().startsWith("late bid") && r.outcome().equals("OK"))
                  .count();
          long stored =
              jdbc.queryForObject(
                  "select count(*) from bid_sos where sos_id = ?", Long.class, f.sosId);
          assertThat(stored)
              .as("bids stored = the first one + the late bids answered OK")
              .isEqualTo(1 + placedOk);
          assertThat(
                  results.stream()
                      .filter(r -> r.step().startsWith("late bid") && !r.outcome().equals("OK")))
              .allMatch(r -> r.outcome().equals("ERR 409"));
          return results;
        });
  }

  /**
   * The mechanic withdraws a bid at the instant the driver accepts it: exactly one of them should
   * win.
   *
   * <p>KNOWN BUG, found by this test over plain REST (so it predates the socket work): both
   * requests answer OK. {@code cancel} takes no lock on the ticket and {@code updateStatus}
   * re-checks the bid's status on an entity it loaded before taking the lock, and neither entity
   * has a version, so the last write wins and the driver and the mechanic are each told they won.
   * Disabled until the service is fixed; enable it to reproduce.
   */
  @Test
  void race_acceptAndWithdrawTheSameBid_exactlyOneWins() throws Exception {
    assertRaceParity(
        false,
        (v, f) -> {
          v.place("m1 bids", f.mech1, f.sosId, "20");
          UUID bid = bidOf(f, f.mechanic1);
          List<Result> results =
              race(
                  List.of(
                      () -> v.decide("accept", f.driver, bid, "ACCEPTED"),
                      () -> v.cancel("withdraw", f.mech1, bid)));
          assertThat(results.stream().filter(r -> r.outcome().equals("OK")).count()).isEqualTo(1);
          String status =
              jdbc.queryForObject("select status from bid_sos where bid_id = ?", String.class, bid);
          Result winner =
              results.stream().filter(r -> r.outcome().equals("OK")).findFirst().orElseThrow();
          assertThat(status).isEqualTo(winner.step().equals("accept") ? "ACCEPTED" : "CANCELLED");
          return results;
        });
  }
}
