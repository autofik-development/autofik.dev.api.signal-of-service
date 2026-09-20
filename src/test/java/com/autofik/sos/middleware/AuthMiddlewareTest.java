package com.autofik.sos.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autofik.sos.security.AuthErrorResponder;
import com.autofik.sos.security.AuthMechanic;
import com.autofik.sos.security.AuthMechanicArgumentResolver;
import com.autofik.sos.security.AuthUser;
import com.autofik.sos.security.AuthUserArgumentResolver;
import com.autofik.sos.security.CurrentUser;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.security.MicroserviceTokenSdk;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole request-scoped path at once — gateway header, filter, middleware, handler parameter —
 * because each piece looks correct on its own and it is the seam between them that decides whether
 * a handler runs as the right person, or as nobody.
 */
class AuthMiddlewareTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final AuthErrorResponder errorResponder = new AuthErrorResponder(objectMapper);

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new ProbeController())
          .addFilters(
              new GatewayIdentityFilter(
                  new MicroserviceTokenSdk(objectMapper), objectMapper, errorResponder))
          .addInterceptors(new AuthMiddleware(errorResponder))
          .setCustomArgumentResolvers(
              new AuthUserArgumentResolver(), new AuthMechanicArgumentResolver())
          .build();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @RestController
  static class ProbeController {
    @GetMapping("/api/v1/probe")
    String whoAmI(@AuthUser CurrentUser caller) {
      return caller == null
          ? "anonymous"
          : caller.id()
              + "/"
              + caller.role()
              + "/"
              + caller.deviceId()
              + "/"
              + caller.serviceCode();
    }

    @GetMapping("/api/v1/probe/mechanic-only")
    String mechanicOnly(@AuthMechanic CurrentUser mechanic) {
      return mechanic == null ? "anonymous" : mechanic.id() + "/" + mechanic.role();
    }
  }

  private static String mechanicContext(String uuid) {
    return "{\"mechanic\":{\"uuid\":\""
        + uuid
        + "\",\"role\":\"MECHANIC\",\"deviceId\":\"pixel-8\",\"otpVerify\":true,"
        + "\"profileVerify\":true,\"isLogout\":false}}";
  }

  private static String context(String uuid, String role, String extraFields) {
    return "{\"user\":{\"uuid\":\""
        + uuid
        + "\",\"role\":\""
        + role
        + "\",\"deviceId\":\"pixel-8\",\"otpVerify\":true,\"profileVerify\":true,"
        + extraFields
        + "}}";
  }

  @Test
  void verifiedContext_reachesTheHandlerAsAuthUser() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            get("/api/v1/probe")
                .header("user-context", context(id.toString(), "GARAGE", "\"isLogout\":false"))
                .header("service-code", "SOS"))
        .andExpect(status().isOk())
        .andExpect(content().string(id + "/MECHANIC/pixel-8/SOS"));
  }

  // Standalone MockMvc has no security chain, so this is the middleware's own answer — the case
  // it exists for, a route reached with nobody behind it.
  @Test
  void noContext_isTurnedAwayBeforeTheHandler() throws Exception {
    mockMvc
        .perform(get("/api/v1/probe"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Missing or invalid user context"))
        .andExpect(jsonPath("$.status").value(401));
  }

  // The filter rejects first, so the middleware never runs and the 403 keeps its own message.
  @Test
  void unverifiedProfile_keepsTheFiltersAnswer() throws Exception {
    String unverified =
        "{\"user\":{\"uuid\":\""
            + UUID.randomUUID()
            + "\",\"role\":\"USER\",\"otpVerify\":true,\"profileVerify\":false}}";

    mockMvc
        .perform(get("/api/v1/probe").header("user-context", unverified))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("User profile is not fully verified"));
  }

  // A browser cannot put identity headers on a preflight; 401ing it would break CORS outright.
  // Driven straight at preHandle: standalone MockMvc has no adapter for Spring's preflight
  // handler, and the guard under test is here rather than in the dispatch.
  @Test
  void preflight_isNotRequiredToCarryAnIdentity() throws Exception {
    MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/v1/probe");
    preflight.addHeader("Origin", "https://dashboard.autofik.dev");
    preflight.addHeader("Access-Control-Request-Method", "GET");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean proceed =
        new AuthMiddleware(errorResponder).preHandle(preflight, response, new Object());

    assertThat(proceed).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  // The legacy headers carry an id and nothing else; the handler must still get a usable caller.
  @Test
  void legacyHeader_stillReachesTheHandler() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(get("/api/v1/probe").header(GatewayIdentityFilter.MECHANIC_HEADER, id.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(id + "/MECHANIC/null/null"));
  }

  @Test
  void mechanicContext_reachesTheHandlerAsAuthMechanic() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            get("/api/v1/probe/mechanic-only")
                .header("mechanic-context", mechanicContext(id.toString())))
        .andExpect(status().isOk())
        .andExpect(content().string(id + "/MECHANIC"));
  }

  // The mechanic app's own header reaches the plain @AuthUser parameter too — it is the same
  // caller, asked for without the role assertion.
  @Test
  void mechanicContext_alsoReachesAPlainAuthUserParameter() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            get("/api/v1/probe")
                .header("mechanic-context", mechanicContext(id.toString()))
                .header("service-code", "SOS"))
        .andExpect(status().isOk())
        .andExpect(content().string(id + "/MECHANIC/pixel-8/SOS"));
  }

  // The whole point of the annotation: a verified driver is still not allowed here, and finds out
  // before the handler body runs rather than from a check the handler forgot to write.
  @Test
  void driver_isRefusedAMechanicOnlyRoute() throws Exception {
    String driver = context(UUID.randomUUID().toString(), "USER", "\"isLogout\":false");

    assertThatThrownBy(
            () ->
                mockMvc.perform(get("/api/v1/probe/mechanic-only").header("user-context", driver)))
        .hasRootCauseInstanceOf(AccessDeniedException.class);
  }

  // A garage account is a mechanic, on this route as everywhere else.
  @Test
  void garageAccount_reachesAMechanicOnlyRoute() throws Exception {
    UUID id = UUID.randomUUID();
    String garage = context(id.toString(), "GARAGE", "\"isLogout\":false");

    mockMvc
        .perform(get("/api/v1/probe/mechanic-only").header("user-context", garage))
        .andExpect(status().isOk())
        .andExpect(content().string(id + "/MECHANIC"));
  }

  // Nobody at all is the middleware's own 401, not the resolver's 403: the two mean different
  // things to the app, and only one of them is fixed by logging in again.
  @Test
  void noContext_isTurnedAwayFromAMechanicOnlyRouteToo() throws Exception {
    mockMvc
        .perform(get("/api/v1/probe/mechanic-only"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Missing or invalid user context"));
  }
}
