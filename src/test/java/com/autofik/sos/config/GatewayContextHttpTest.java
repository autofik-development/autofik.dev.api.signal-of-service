package com.autofik.sos.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Postman "Gateway Context Auth" cases, run over the real security chain and MVC config.
 *
 * <p>Each unit test proves one piece of the identity path; this proves the pieces answer an actual
 * HTTP request the way the app sees it — and, for the accepted cases, that the id the controller
 * acts for is the one the header named, not merely that something got through.
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
class GatewayContextHttpTest {

  private static final String DRIVER = "11111111-1111-1111-1111-111111111111";
  private static final String MECHANIC = "33333333-3333-3333-3333-333333333333";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SosService sosService;
  @MockitoBean private BidService bidService;
  @MockitoBean private MechanicService mechanicService;
  @MockitoBean private FeedbackService feedbackService;
  @MockitoBean private SosMediaService sosMediaService;

  private static String block(String key, String uuid, String role, String flags) {
    String rolePart = role == null ? "" : "\"role\":\"" + role + "\",";
    return "{\"" + key + "\":{\"uuid\":\"" + uuid + "\"," + rolePart + flags + "}}";
  }

  private static final String OK =
      "\"otpVerify\":true,\"profileVerify\":true,\"tokenStatus\":\"active\",\"isLogout\":false";

  private static String user(String role, String flags) {
    return block("user", DRIVER, role, flags);
  }

  private static String mechanic(String uuid, String role) {
    return block("mechanic", uuid, role, OK);
  }

  /** name, headers, expected status, expected message (null = none), id the controller acts for. */
  static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of("no context", Map.of(), 401, "Missing or invalid user context", null),
        Arguments.of("valid driver", Map.of("user-context", user("USER", OK)), 200, null, DRIVER),
        Arguments.of(
            "valid mechanic, no role",
            Map.of("mechanic-context", mechanic(MECHANIC, null)),
            200,
            null,
            MECHANIC),
        Arguments.of(
            "mechanic block inside user-context",
            Map.of("user-context", mechanic(MECHANIC, "MECHANIC")),
            200,
            null,
            MECHANIC),
        Arguments.of("GARAGE role", Map.of("user-context", user("GARAGE", OK)), 200, null, DRIVER),
        Arguments.of(
            "CUSTOMER role", Map.of("user-context", user("CUSTOMER", OK)), 200, null, DRIVER),
        Arguments.of(
            "logged out",
            Map.of(
                "user-context",
                user("USER", OK.replace("\"isLogout\":false", "\"isLogout\":true"))),
            401,
            "Access token is no longer valid",
            null),
        Arguments.of(
            "token expired",
            Map.of("user-context", user("USER", OK.replace("active", "expired"))),
            401,
            "Access token is no longer valid",
            null),
        Arguments.of(
            "otp not verified",
            Map.of(
                "user-context",
                user("USER", OK.replace("\"otpVerify\":true", "\"otpVerify\":false"))),
            403,
            "User profile is not fully verified",
            null),
        Arguments.of(
            "profile not verified",
            Map.of(
                "user-context",
                user("USER", OK.replace("\"profileVerify\":true", "\"profileVerify\":false"))),
            403,
            "User profile is not fully verified",
            null),
        Arguments.of(
            "unknown role ADMIN",
            Map.of("user-context", user("ADMIN", OK)),
            403,
            "Role 'ADMIN' is not permitted to use this service",
            null),
        Arguments.of(
            "driver role in mechanic-context",
            Map.of("mechanic-context", mechanic(MECHANIC, "USER")),
            403,
            "Role 'USER' does not belong in a MECHANIC context",
            null),
        Arguments.of(
            "conflicting ids",
            Map.of("user-context", user("USER", OK), "mechanic-context", mechanic(MECHANIC, null)),
            401,
            "Conflicting identity context",
            null),
        Arguments.of(
            "same id in both headers",
            Map.of("user-context", user("USER", OK), "mechanic-context", mechanic(DRIVER, null)),
            200,
            null,
            DRIVER),
        Arguments.of(
            "malformed JSON",
            Map.of("user-context", "{not json"),
            401,
            "Missing or invalid user context",
            null),
        Arguments.of(
            "uuid not a UUID",
            Map.of("user-context", block("user", "not-a-uuid", "USER", OK)),
            401,
            "Missing or invalid user context",
            null),
        Arguments.of(
            "legacy X-User-Id is ignored",
            Map.of("X-User-Id", DRIVER),
            401,
            "Missing or invalid user context",
            null),
        Arguments.of(
            "legacy X-Mechanic-Id is ignored",
            Map.of("X-Mechanic-Id", MECHANIC),
            401,
            "Missing or invalid user context",
            null),
        Arguments.of(
            "both legacy headers",
            Map.of("X-User-Id", DRIVER, "X-Mechanic-Id", MECHANIC),
            401,
            "Missing or invalid user context",
            null),
        Arguments.of(
            "empty X-Mechanic-Id (the Postman bug)",
            Map.of("X-Mechanic-Id", ""),
            401,
            "Missing or invalid user context",
            null));
  }

  @ParameterizedTest(name = "{0} -> {2}")
  @MethodSource("cases")
  void gatewayContext(
      String name, Map<String, String> headers, int status, String message, String actsFor)
      throws Exception {
    MockHttpServletRequestBuilder request = get("/api/v1/sos");
    headers.forEach(request::header);

    var result = mockMvc.perform(request).andExpect(status().is(status));

    if (message != null) {
      result.andExpect(jsonPath("$.message").value(message));
    }
    if (actsFor != null) {
      verify(sosService).getByUser(eq(UUID.fromString(actsFor)), any(Pageable.class));
    } else {
      verifyNoInteractions(sosService);
    }
  }

  private static final String ANY = "44444444-4444-4444-4444-444444444444";
  private static final String DRIVER_HEADER = user("USER", OK);
  private static final String MECHANIC_HEADER = mechanic(MECHANIC, null);

  /**
   * Every route that acts for a caller, with a body valid enough to reach the role check — a body
   * that fails {@code @Valid} answers 400 first, which would hide a missing 403.
   *
   * <p>route name, request, who may call it: USER, MECHANIC, or BOTH.
   */
  static Stream<Arguments> routes() {
    String m = "/api/v1/mechanics/" + MECHANIC;
    return Stream.of(
        // driver-only
        route(
            "POST /sos",
            post("/api/v1/sos")
                .content(
                    "{\"userId\":\""
                        + DRIVER
                        + "\",\"serviceGroupId\":\"g\",\"latitude\":11.55,"
                        + "\"longitude\":104.92,\"vehicleId\":\""
                        + ANY
                        + "\"}"),
            "USER"),
        route(
            "PATCH /sos/completion",
            patch("/api/v1/sos/completion")
                .content(
                    "{\"userId\":\""
                        + DRIVER
                        + "\",\"historyId\":\""
                        + ANY
                        + "\",\"approvedStatus\":\"ACCEPTED\"}"),
            "USER"),
        route("PATCH /sos/{id}/cancel", patch("/api/v1/sos/" + ANY + "/cancel"), "USER"),
        route("POST /sos/{id}/rebroadcast", post("/api/v1/sos/" + ANY + "/rebroadcast"), "USER"),
        route(
            "PATCH /bids/{id}/status",
            patch("/api/v1/bids/" + ANY + "/status")
                .content("{\"userId\":\"" + DRIVER + "\",\"status\":\"ACCEPTED\"}"),
            "USER"),
        route(
            "POST /feedback",
            post("/api/v1/feedback")
                .content(
                    "{\"userId\":\""
                        + DRIVER
                        + "\",\"mechanicId\":\""
                        + MECHANIC
                        + "\",\"sosId\":\""
                        + ANY
                        + "\",\"rating\":5}"),
            "USER"),
        route(
            "GET /mechanics/available",
            get("/api/v1/mechanics/available").param("lat", "11.55").param("lng", "104.92"),
            "USER"),
        // mechanic-only
        route(
            "PATCH /sos/{id}/status",
            patch("/api/v1/sos/" + ANY + "/status").param("toStatus", "ON_THE_WAY"),
            "MECHANIC"),
        route(
            "POST /sos/{id}/completion",
            post("/api/v1/sos/" + ANY + "/completion")
                .content("{\"mechanicId\":\"" + MECHANIC + "\",\"toStatus\":\"COMPLETED\"}"),
            "MECHANIC"),
        route(
            "POST /bids",
            post("/api/v1/bids")
                .content(
                    "{\"mechanicId\":\"" + MECHANIC + "\",\"sosId\":\"" + ANY + "\",\"price\":10}"),
            "MECHANIC"),
        route("GET /bids/mechanic/{id}", get("/api/v1/bids/mechanic/" + MECHANIC), "MECHANIC"),
        route("PATCH /bids/{id}/cancel", patch("/api/v1/bids/" + ANY + "/cancel"), "MECHANIC"),
        route(
            "POST /mechanics",
            post("/api/v1/mechanics")
                .content(
                    "{\"fullName\":\"M\",\"phoneNumber\":\"+855100000011\",\"email\":\"m@example.com\","
                        + "\"mechanicRole\":\"FREELANCE\",\"latitude\":11.55,\"longitude\":104.92}"),
            "MECHANIC"),
        route(
            "POST /mechanics/{id}/skills",
            post(m + "/skills").content("{\"skillTags\":[\"TIRE\"]}"),
            "MECHANIC"),
        route(
            "PUT /mechanics/{id}/skills/{skillId}",
            put(m + "/skills/" + ANY).content("{\"skillTags\":[\"TIRE\"]}"),
            "MECHANIC"),
        route("DELETE /mechanics/{id}/skills/{skillId}", delete(m + "/skills/" + ANY), "MECHANIC"),
        route(
            "PATCH /mechanics/{id}/location",
            patch(m + "/location").param("lat", "11.55").param("lng", "104.92"),
            "MECHANIC"),
        route(
            "PATCH /mechanics/{id}/availability",
            patch(m + "/availability").param("available", "true"),
            "MECHANIC"),
        // either role
        route("GET /sos", get("/api/v1/sos"), "BOTH"),
        route("GET /sos/{id}", get("/api/v1/sos/" + ANY), "BOTH"),
        route(
            "GET /sos/{id}/nearby-mechanics",
            get("/api/v1/sos/" + ANY + "/nearby-mechanics"),
            "BOTH"),
        route("GET /bids/sos/{id}", get("/api/v1/bids/sos/" + ANY), "BOTH"),
        route("GET /mechanics/{id}/location", get(m + "/location"), "BOTH"),
        route("GET /sos/{id}/media", get("/api/v1/sos/" + ANY + "/media"), "BOTH"));
  }

  private static Arguments route(String name, MockHttpServletRequestBuilder request, String who) {
    return Arguments.of(name, request.contentType(MediaType.APPLICATION_JSON), who);
  }

  @ParameterizedTest(name = "{0} as driver")
  @MethodSource("routes")
  void routeAsDriver(String name, MockHttpServletRequestBuilder request, String who)
      throws Exception {
    expectRoleOutcome(request.header("user-context", DRIVER_HEADER), !who.equals("MECHANIC"));
  }

  @ParameterizedTest(name = "{0} as mechanic")
  @MethodSource("routes")
  void routeAsMechanic(String name, MockHttpServletRequestBuilder request, String who)
      throws Exception {
    expectRoleOutcome(request.header("mechanic-context", MECHANIC_HEADER), !who.equals("USER"));
  }

  private void expectRoleOutcome(MockHttpServletRequestBuilder request, boolean allowed)
      throws Exception {
    if (allowed) {
      mockMvc.perform(request).andExpect(status().is2xxSuccessful());
    } else {
      mockMvc
          .perform(request)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("You are not allowed to perform this action"));
      // A refused caller must not reach the service at all, not just get a 403 after it ran.
      verifyNoInteractions(
          sosService, bidService, mechanicService, feedbackService, sosMediaService);
    }
  }
}
