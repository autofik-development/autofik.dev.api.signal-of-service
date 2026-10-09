package com.autofik.sos.fuzz;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.autofik.sos.config.SecurityConfig;
import com.autofik.sos.config.WebConfig;
import com.autofik.sos.controller.BidController;
import com.autofik.sos.controller.FeedbackController;
import com.autofik.sos.controller.MechanicController;
import com.autofik.sos.controller.SosController;
import com.autofik.sos.controller.SosMediaController;
import com.autofik.sos.middleware.AuthMiddleware;
import com.autofik.sos.security.AuthErrorResponder;
import com.autofik.sos.security.AuthMechanicArgumentResolver;
import com.autofik.sos.security.AuthUserArgumentResolver;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.security.MicroserviceTokenSdk;
import com.autofik.sos.service.BidService;
import com.autofik.sos.service.FeedbackService;
import com.autofik.sos.service.MechanicService;
import com.autofik.sos.service.SosMediaService;
import com.autofik.sos.service.SosService;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Hostile input over the real security chain and MVC config, with the services mocked out. The
 * invariant is the same everywhere: bad input from a client is the client's error (4xx), never ours
 * (5xx).
 *
 * <p>Plain JUnit over a seeded generator rather than jqwik properties, because a Spring slice and
 * jqwik do not share a lifecycle. The seed is fixed so a PR build is deterministic; a nightly job
 * raises {@code -Dfuzz.tries} and passes a fresh {@code -Dfuzz.seed}.
 */
@WebMvcTest(
    controllers = {
      SosController.class,
      BidController.class,
      MechanicController.class,
      FeedbackController.class,
      SosMediaController.class
    })
@Import({
  WebConfig.class,
  SecurityConfig.class,
  AuthMiddleware.class,
  AuthUserArgumentResolver.class,
  AuthMechanicArgumentResolver.class,
  AuthErrorResponder.class,
  MicroserviceTokenSdk.class,
  GatewayIdentityFilter.class
})
class RestFuzzTest {

  private static final String DRIVER_HEADER =
      "{\"user\":{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"role\":\"USER\","
          + "\"otpVerify\":true,\"profileVerify\":true,\"tokenStatus\":\"active\","
          + "\"isLogout\":false}}";
  private static final String MECHANIC_HEADER =
      "{\"mechanic\":{\"uuid\":\"33333333-3333-3333-3333-333333333333\","
          + "\"otpVerify\":true,\"profileVerify\":true,\"tokenStatus\":\"active\","
          + "\"isLogout\":false}}";

  private static final int TRIES = Integer.getInteger("fuzz.tries", 150);
  private static final long SEED = Long.getLong("fuzz.seed", 20261002L);

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SosService sosService;
  @MockitoBean private BidService bidService;
  @MockitoBean private MechanicService mechanicService;
  @MockitoBean private FeedbackService feedbackService;
  @MockitoBean private SosMediaService sosMediaService;

  // ── generators ─────────────────────────────────────────────────────────────

  private static final List<String> NASTY =
      List.of(
          "",
          " ",
          "null",
          "0",
          "-1",
          "2147483648",
          "99999999999999999999",
          "NaN",
          "\u0000",
          "%00",
          "%",
          "../../etc/passwd",
          "' OR 1=1 --",
          "${jndi:ldap://x/a}",
          "not-a-uuid",
          "00000000-0000-0000-0000-000000000000",
          "11111111-1111-1111-1111-111111111111",
          "11111111-1111-1111-1111-11111111111G",
          "PENDING",
          "pending",
          "NOPE",
          "\uD83D\uDE00",
          "a".repeat(10_000));

  private static final List<String> NASTY_JSON =
      List.of(
          "{}",
          "[]",
          "null",
          "\"\"",
          "0",
          "{",
          "}{",
          "{\"latitude\":1e999,\"longitude\":-1e999}",
          "{\"latitude\":\"x\",\"longitude\":null}",
          "{\"vehicleId\":\"not-a-uuid\"}",
          "{\"vehicleId\":1}",
          "{\"broadcastRadiusKm\":2147483648}",
          "{\"broadcastRadiusKm\":-1}",
          "{\"problemType\":{}}",
          "{\"userId\":\"11111111-1111-1111-1111-111111111111\"}",
          "{" + "\"a\":".repeat(3_000) + "1}",
          "[".repeat(5_000));

  private static String randomString(Random r) {
    int len = r.nextInt(40);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) {
      // Mix printable ASCII, control characters and a few multi-byte code points.
      int kind = r.nextInt(10);
      if (kind < 6) sb.append((char) (32 + r.nextInt(95)));
      else if (kind < 8) sb.append((char) r.nextInt(32));
      else sb.appendCodePoint(0x80 + r.nextInt(0x1F000));
    }
    return sb.toString();
  }

  private static Stream<String> strings() {
    Random r = new Random(SEED);
    List<String> all = new ArrayList<>(NASTY);
    for (int i = 0; i < TRIES; i++) all.add(randomString(r));
    return all.stream();
  }

  // The endpoints added in Phase 2 are many, and each runs as both roles; fewer cases each keeps a
  // PR build fast. A nightly run raises -Dfuzz.tries to go as deep as the original ones.
  private static final int TRIES_PER_ENDPOINT = Math.max(30, TRIES / 4);

  private static Stream<String> fewStrings() {
    Random r = new Random(SEED + 2);
    List<String> all = new ArrayList<>(NASTY);
    for (int i = 0; i < TRIES_PER_ENDPOINT; i++) all.add(randomString(r));
    return all.stream();
  }

  private static Stream<String> fewJsonBodies() {
    Random r = new Random(SEED + 3);
    List<String> all = new ArrayList<>(NASTY_JSON);
    for (int i = 0; i < TRIES_PER_ENDPOINT; i++) all.add(randomString(r));
    return all.stream();
  }

  private static Stream<String> jsonBodies() {
    Random r = new Random(SEED + 1);
    List<String> all = new ArrayList<>(NASTY_JSON);
    for (int i = 0; i < TRIES; i++) all.add(randomString(r));
    return all.stream();
  }

  // ── properties ─────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "create body #{index}")
  @MethodSource("jsonBodies")
  void createSos_neverReturns5xx(String body) {
    assertNo5xx(
        post("/api/v1/sos")
            .header("user-context", DRIVER_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
        body);
  }

  @ParameterizedTest(name = "get by id #{index}")
  @MethodSource("strings")
  void getSosById_neverReturns5xx(String id) {
    assertNo5xx(get("/api/v1/sos/{id}", id).header("user-context", DRIVER_HEADER), id);
  }

  @ParameterizedTest(name = "list status #{index}")
  @MethodSource("strings")
  void listSos_badStatusParam_neverReturns5xx(String status) {
    assertNo5xx(
        get("/api/v1/sos").param("status", status).header("user-context", DRIVER_HEADER), status);
  }

  @ParameterizedTest(name = "list paging #{index}")
  @MethodSource("strings")
  void listSos_badPaging_neverReturns5xx(String value) {
    assertNo5xx(
        get("/api/v1/sos")
            .param("page", value)
            .param("size", value)
            .header("user-context", DRIVER_HEADER),
        value);
  }

  @ParameterizedTest(name = "update status #{index}")
  @MethodSource("strings")
  void updateStatus_badIdOrStatus_neverReturns5xx(String value) {
    assertNo5xx(
        patch("/api/v1/sos/{id}/status", value)
            .param("toStatus", value)
            .header("mechanic-context", MECHANIC_HEADER),
        value);
  }

  @ParameterizedTest(name = "identity header #{index}")
  @MethodSource("strings")
  void identityHeaders_neverCause5xx(String header) {
    // Header values cannot hold control characters on the wire; the servlet layer rejects those
    // before they reach us, so test what a real client could actually send.
    String safe = header.replaceAll("[\\x00-\\x1f\\x7f]", "");
    assertNo5xx(get("/api/v1/sos").header("user-context", safe), header);
    assertNo5xx(get("/api/v1/sos").header("mechanic-context", safe), header);
  }

  // ── Phase 2: bids, feedback, mechanics, media ──────────────────────────────

  @ParameterizedTest(name = "bid create #{index}")
  @MethodSource("fewJsonBodies")
  void bids_create_neverReturns5xx(String body) {
    asBothRoles(
        () -> post("/api/v1/bids").contentType(MediaType.APPLICATION_JSON).content(body), body);
  }

  @ParameterizedTest(name = "bid status #{index}")
  @MethodSource("fewJsonBodies")
  void bids_updateStatusBody_neverReturns5xx(String body) {
    asBothRoles(
        () ->
            patch("/api/v1/bids/{id}/status", "22222222-2222-2222-2222-222222222222")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        body);
  }

  @ParameterizedTest(name = "bid ids #{index}")
  @MethodSource("fewStrings")
  void bids_badIdsAndSort_neverReturn5xx(String value) {
    asBothRoles(() -> get("/api/v1/bids/sos/{id}", value).param("sortBy", value), value);
    asBothRoles(() -> get("/api/v1/bids/mechanic/{id}", value), value);
    asBothRoles(() -> patch("/api/v1/bids/{id}/status", value).content("{}"), value);
    asBothRoles(() -> patch("/api/v1/bids/{id}/cancel", value), value);
  }

  @ParameterizedTest(name = "feedback create #{index}")
  @MethodSource("fewJsonBodies")
  void feedback_create_neverReturns5xx(String body) {
    asBothRoles(
        () -> post("/api/v1/feedback").contentType(MediaType.APPLICATION_JSON).content(body), body);
  }

  @ParameterizedTest(name = "feedback reads #{index}")
  @MethodSource("fewStrings")
  void feedback_badIdsAndPaging_neverReturn5xx(String value) {
    asBothRoles(() -> get("/api/v1/feedback/sos/{id}", value), value);
    asBothRoles(
        () ->
            get("/api/v1/feedback/mechanic/{id}", "33333333-3333-3333-3333-333333333333")
                .param("page", value)
                .param("size", value)
                .param("sort", value),
        value);
    asBothRoles(() -> get("/api/v1/feedback/mechanic/{id}", value), value);
  }

  @ParameterizedTest(name = "mechanic params #{index}")
  @MethodSource("fewStrings")
  void mechanics_badPathAndQueryParams_neverReturn5xx(String value) {
    asBothRoles(() -> get("/api/v1/mechanics/{id}", value), value);
    asBothRoles(() -> get("/api/v1/mechanics/{id}/skills", value), value);
    asBothRoles(() -> get("/api/v1/mechanics/{id}/location", value), value);
    asBothRoles(
        () ->
            get("/api/v1/mechanics/available")
                .param("lat", value)
                .param("lng", value)
                .param("radiusKm", value)
                .param("problemType", value),
        value);
    asBothRoles(
        () ->
            patch("/api/v1/mechanics/{id}/location", value)
                .param("lat", value)
                .param("lng", value)
                .param("sosId", value),
        value);
    asBothRoles(
        () -> patch("/api/v1/mechanics/{id}/availability", value).param("available", value), value);
  }

  @ParameterizedTest(name = "media #{index}")
  @MethodSource("fewStrings")
  void media_badIdsAndUploads_neverReturn5xx(String value) {
    asBothRoles(() -> get("/api/v1/sos/{id}/media", value), value);
    // The file's name and declared type are attacker-controlled, as is its body.
    uploadAsBothRoles(
        () ->
            multipart("/api/v1/sos/{id}/media", "11111111-1111-1111-1111-111111111111")
                .file(new MockMultipartFile("file", value, value, value.getBytes())),
        value);
    uploadAsBothRoles(
        () ->
            multipart("/api/v1/sos/{id}/media", value)
                .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0])),
        value);
  }

  // ── transport-level mistakes: wrong method, content type, Accept ────────────

  @ParameterizedTest(name = "content type #{index}")
  @MethodSource("fewStrings")
  void wrongContentType_neverReturns5xx(String contentType) {
    // Spring Security's firewall rejects control and non-ASCII header values before they reach us
    // (a 400 on a real server), so only printable ASCII is a meaningful header here.
    String type = contentType.replaceAll("[^\\x20-\\x7e]", "");
    asBothRoles(
        () -> post("/api/v1/sos").header("Content-Type", type).content("{\"vehicleId\":\"x\"}"),
        contentType);
    asBothRoles(() -> get("/api/v1/sos").header("Accept", type), contentType);
  }

  @ParameterizedTest(name = "method #{index}")
  @MethodSource("fewStrings")
  void wrongHttpMethod_neverReturns5xx(String id) {
    asBothRoles(() -> put("/api/v1/sos/{id}", id).content("{}"), id);
    asBothRoles(() -> delete("/api/v1/sos/{id}", id), id);
    asBothRoles(() -> post("/api/v1/sos/{id}/status", id), id);
    asBothRoles(() -> put("/api/v1/feedback"), id);
  }

  @org.junit.jupiter.api.Test
  void aFailureLine_carriesEverythingNeededToReplayTheRequest() {
    String line =
        reproduction(
            post("/api/v1/sos")
                .header("user-context", DRIVER_HEADER)
                .header("Content-Type", "application/json")
                .content("{\"vehicleId\":\"not-a-uuid\"}"));

    org.assertj.core.api.Assertions.assertThat(line)
        .contains("POST /api/v1/sos")
        .contains("user-context")
        .contains("vehicleId");
  }

  // ── helper ─────────────────────────────────────────────────────────────────

  private void uploadAsBothRoles(
      Supplier<MockMultipartHttpServletRequestBuilder> request, String input) {
    assertNo5xx(request.get().header("user-context", DRIVER_HEADER), input);
    assertNo5xx(request.get().header("mechanic-context", MECHANIC_HEADER), input);
  }

  /** Runs the request once as a driver and once as a mechanic, so neither role gate hides it. */
  private void asBothRoles(Supplier<MockHttpServletRequestBuilder> request, String input) {
    assertNo5xx(request.get().header("user-context", DRIVER_HEADER), input);
    assertNo5xx(request.get().header("mechanic-context", MECHANIC_HEADER), input);
  }

  private void assertNo5xx(RequestBuilder request, String input) {
    int status;
    try {
      status = mockMvc.perform(request).andReturn().getResponse().getStatus();
    } catch (Exception e) {
      // MockMvc rethrows what the app failed to turn into a response — in production that is a 500.
      String repro = reproduction(request);
      System.out.println(
          "[fuzz-repro] seed=" + SEED + " unhandled " + e.getClass().getSimpleName() + " " + repro);
      throw new AssertionError(
          "Unhandled "
              + e.getClass().getSimpleName()
              + " for input: "
              + abbreviate(input)
              + " -> "
              + e.getMessage()
              + " | Reproduce: "
              + repro,
          e);
    }
    if (status >= 500) {
      String repro = reproduction(request);
      System.out.println("[fuzz-repro] seed=" + SEED + " status=" + status + " " + repro);
      throw new AssertionError(
          "status " + status + " for input: " + abbreviate(input) + " | Reproduce: " + repro);
    }
  }

  /**
   * The request as a replayable line: method, URL, headers and body, enough to send it by hand
   * (curl, Postman) against a running instance. Header values here are test identities only.
   */
  private static String reproduction(RequestBuilder request) {
    try {
      var req = request.buildRequest(new org.springframework.mock.web.MockServletContext());
      StringBuilder headers = new StringBuilder();
      java.util.Collections.list(req.getHeaderNames())
          .forEach(
              h ->
                  headers
                      .append(h)
                      .append(": ")
                      .append(abbreviate(req.getHeader(h)))
                      .append(" | "));
      String query = req.getQueryString() == null ? "" : "?" + abbreviate(req.getQueryString());
      return req.getMethod()
          + " "
          + abbreviate(req.getRequestURI())
          + query
          + " headers[ "
          + headers
          + "] body="
          + abbreviate(
              new String(req.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception e) {
      return "(could not render the request: " + e + ")";
    }
  }

  private static String abbreviate(String s) {
    String shown = s.length() > 120 ? s.substring(0, 120) + "...(" + s.length() + " chars)" : s;
    return shown.replace("\n", "\\n").replace("\u0000", "\\0");
  }
}
