package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

  // ── helper ─────────────────────────────────────────────────────────────────

  private void assertNo5xx(MockHttpServletRequestBuilder request, String input) {
    int status;
    try {
      status = mockMvc.perform(request).andReturn().getResponse().getStatus();
    } catch (Exception e) {
      // MockMvc rethrows what the app failed to turn into a response — in production that is a 500.
      throw new AssertionError(
          "Unhandled "
              + e.getClass().getSimpleName()
              + " for input: "
              + abbreviate(input)
              + " -> "
              + e.getMessage(),
          e);
    }
    assertThat(status).as("status for input: %s", abbreviate(input)).isLessThan(500);
  }

  private static String abbreviate(String s) {
    String shown = s.length() > 120 ? s.substring(0, 120) + "...(" + s.length() + " chars)" : s;
    return shown.replace("\n", "\\n").replace("\u0000", "\\0");
  }
}
