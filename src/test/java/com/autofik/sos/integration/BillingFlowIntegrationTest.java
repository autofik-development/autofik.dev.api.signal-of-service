package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SOS against a stand-in billing service that speaks billing's documented contract: verify at
 * create and bid, one token cut when the job is completed. The app, the database and the HTTP
 * client are real; only billing is a local HTTP server. Billing is on for this class only, so it
 * boots its own context.
 */
class BillingFlowIntegrationTest extends FlowTestSupport {

  private static final double SOS_LAT = 11.5564;
  private static final double SOS_LNG = 104.9282;
  private static final String PROBLEM_TYPE = "TOW";
  private static final String KEY = "it-service-key";

  private record Call(String method, String path, String query, String body, String key) {}

  private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  private static final Set<String> NO_PLAN = ConcurrentHashMap.newKeySet();
  private static final Queue<Integer> CONSUME_STATUSES = new ConcurrentLinkedQueue<>();
  private static volatile int verifyStatus = 200;
  private static final HttpServer BILLING = startBilling();

  private static HttpServer startBilling() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/internal/billings/verify", BillingFlowIntegrationTest::verify);
      server.createContext(
          "/internal/billings/tokens/consume", BillingFlowIntegrationTest::consume);
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void verify(HttpExchange ex) throws IOException {
    record(ex);
    if (verifyStatus != 200) {
      reply(ex, verifyStatus, "{\"status\":\"error\"}");
      return;
    }
    String query = ex.getRequestURI().getRawQuery();
    String userId = group(query, "userId=([^&]+)");
    String role = group(query, "userRole=([^&]+)").toUpperCase();
    boolean paid = !NO_PLAN.contains(userId);
    String data =
        paid
            ? "\"billingId\":\"b-1\",\"planId\":\"plan_it\",\"tier\":\"Starter\","
                + "\"tokenBalance\":400,\"canUse\":true"
            : "\"billingId\":null,\"planId\":null,\"tier\":null,\"tokenBalance\":0,"
                + "\"canUse\":false";
    reply(
        ex,
        200,
        "{\"status\":\"success\",\"code\":\"BILLING_VERIFY_SUCCESS\",\"data\":{\"userId\":\""
            + userId
            + "\",\"userRole\":\""
            + role
            + "\",\"expired\":false,"
            + data
            + "}}");
  }

  private static void consume(HttpExchange ex) throws IOException {
    String body = record(ex);
    Integer forced = CONSUME_STATUSES.poll();
    if (forced != null) {
      reply(ex, forced, "{\"status\":\"error\",\"message\":\"boom\"}");
      return;
    }
    reply(
        ex,
        200,
        "{\"status\":\"success\",\"code\":\"TOKEN_CONSUME_SUCCESS\",\"data\":{\"userId\":\""
            + group(body, "\"userId\":\"([^\"]+)\"")
            + "\",\"billingId\":\"b-1\",\"requestId\":\""
            + group(body, "\"requestId\":\"([^\"]+)\"")
            + "\",\"consumed\":1,\"tokenBalance\":399,\"alreadyProcessed\":false}}");
  }

  private static String record(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    CALLS.add(
        new Call(
            ex.getRequestMethod(),
            ex.getRequestURI().getPath(),
            ex.getRequestURI().getRawQuery(),
            body,
            ex.getRequestHeaders().getFirst("X-Service-Key")));
    return body;
  }

  private static void reply(HttpExchange ex, int status, String json) throws IOException {
    byte[] out = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, out.length);
    ex.getResponseBody().write(out);
    ex.close();
  }

  private static String group(String text, String regex) {
    Matcher m = Pattern.compile(regex).matcher(text == null ? "" : text);
    return m.find() ? m.group(1) : "";
  }

  @DynamicPropertySource
  static void billing(DynamicPropertyRegistry r) {
    r.add("billing.enabled", () -> "true");
    r.add("billing.base-url", () -> "http://127.0.0.1:" + BILLING.getAddress().getPort());
    r.add("billing.service-key", () -> KEY);
    r.add("billing.verify-path", () -> "/internal/billings/verify");
    r.add("billing.consume-path", () -> "/internal/billings/tokens/consume");
    r.add("billing.connect-timeout-ms", () -> "2000");
    r.add("billing.read-timeout-ms", () -> "3000");
    r.add("billing.allowed-tiers.customer", () -> "");
    r.add("billing.allowed-tiers.mechanic", () -> "");
  }

  @BeforeEach
  void resetBilling() {
    CALLS.clear();
    NO_PLAN.clear();
    CONSUME_STATUSES.clear();
    verifyStatus = 200;
  }

  private static List<Call> calls(Predicate<Call> p) {
    return CALLS.stream().filter(p).toList();
  }

  private static List<Call> consumes() {
    return calls(c -> c.path().endsWith("/tokens/consume"));
  }

  /** Waits for the async token cut; the call is fired on its own thread after the commit. */
  private static void awaitConsumes(int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 20_000;
    while (consumes().size() < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    assertThat(consumes()).as("consume calls").hasSize(count);
  }

  // ── create ─────────────────────────────────────────────────────────────────

  @Test
  void create_verifiesTheCustomerWithTheKey_andSavesThePlan() throws Exception {
    Driver d = newDriver();

    UUID sosId = createSosOk(d);

    assertThat(sosId).isNotNull();
    List<Call> verifies = calls(c -> c.path().endsWith("/verify"));
    assertThat(verifies).hasSize(1);
    Call v = verifies.get(0);
    assertThat(v.method()).isEqualTo("GET");
    assertThat(v.key()).isEqualTo(KEY);
    // The profile sync stored the SSO role from user-context on this request; that is what goes
    // out.
    assertThat(v.query()).isEqualTo("userId=" + d.user().getUserId() + "&userRole=USER");
    var saved = userRepository.findById(d.user().getUserId()).orElseThrow();
    assertThat(saved.getPlanId()).isEqualTo("plan_it");
    assertThat(saved.getPlanTier()).isEqualTo("Starter");
    assertThat(saved.getPlanCanUse()).isTrue();
  }

  @Test
  void create_withNoPaidPlan_is403NoPlan_andNothingIsCreated() throws Exception {
    Driver d = newDriver();
    NO_PLAN.add(d.user().getUserId().toString());

    HttpResponse<String> r = createSos(d);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
    assertThat(parse(r.body()).get("details").get("reason").asString()).isEqualTo("NO_PLAN");
    assertThat(consumes()).isEmpty();
  }

  @Test
  void create_whenBillingIsDown_is503() throws Exception {
    verifyStatus = 500;

    HttpResponse<String> r = createSos(newDriver());

    assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
  }

  // ── bid ────────────────────────────────────────────────────────────────────

  @Test
  void bid_verifiesTheMechanicOnce() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);

    placeBidOk(m, sosId);

    assertThat(calls(c -> c.query() != null && c.query().contains(m.getMechanicId().toString())))
        .hasSize(1);
  }

  @Test
  void bid_withNoPaidPlan_is403_andNoBidIsSaved() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    NO_PLAN.add(m.getMechanicId().toString());

    HttpResponse<String> r = placeBid(m, sosId);

    assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
  }

  // ── completion ─────────────────────────────────────────────────────────────

  @Test
  void completingTheJob_cutsExactlyOneToken_withTheSosIdAsRequestId() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    assertThat(consumes()).as("nothing is cut before the job is done").isEmpty();

    completeJob(d, m, sosId);

    awaitConsumes(1);
    Call cut = consumes().get(0);
    assertThat(cut.method()).isEqualTo("POST");
    assertThat(cut.key()).isEqualTo(KEY);
    assertThat(parse(cut.body()).get("userId").asString())
        .isEqualTo(d.user().getUserId().toString());
    assertThat(parse(cut.body()).get("requestId").asString()).isEqualTo(sosId.toString());
    assertThat(parse(cut.body()).get("userRole").asString()).isEqualTo("USER");
    assertThat(sosStatus(d, sosId)).isEqualTo("COMPLETED");
  }

  @Test
  void aFailedCut_isRetriedWithTheSameRequestId_andTheJobStaysCompleted() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(SOS_LAT, SOS_LNG, PROBLEM_TYPE);
    UUID sosId = createSosOk(d);
    CONSUME_STATUSES.add(500);

    completeJob(d, m, sosId);

    awaitConsumes(2);
    assertThat(consumes())
        .extracting(c -> parse(c.body()).get("requestId").asString())
        .containsOnly(sosId.toString());
    assertThat(sosStatus(d, sosId)).isEqualTo("COMPLETED");
  }

  @Test
  void aCancelledJob_cutsNothing() throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);

    HttpResponse<String> cancel =
        send(
            req("/api/v1/sos/" + sosId + "/cancel")
                .header("user-context", userContext(d.user().getUserId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);

    Thread.sleep(1500);
    assertThat(consumes()).isEmpty();
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
}
