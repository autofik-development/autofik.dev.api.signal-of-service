package com.autofik.sos.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autofik.sos.controller.HealthController;
import com.autofik.sos.middleware.AuthMiddleware;
import com.autofik.sos.security.AuthErrorResponder;
import com.autofik.sos.security.AuthMechanicArgumentResolver;
import com.autofik.sos.security.AuthUserArgumentResolver;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.security.MicroserviceTokenSdk;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Loads the real MVC configuration, because every piece of this can pass its own unit test and
 * still leave the app refusing to start — {@link WebConfig} is a WebMvcConfigurer that now depends
 * on beans of its own, and an interceptor registered against the wrong prefix locks out either the
 * whole API or the load balancer.
 */
@WebMvcTest(controllers = HealthController.class)
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
class WebConfigWiringTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JdbcTemplate jdbcTemplate;

  // The load balancer carries no identity and must never be asked for one.
  @Test
  void healthEndpointStaysOpen() throws Exception {
    mockMvc.perform(get("/healthy")).andExpect(status().isOk());
  }

  // 401, not the bodiless 403 Spring Security answers with by default: the gateway named nobody,
  // which is a different thing from naming someone who is not allowed here.
  @Test
  void apiRoutesRequireACaller() throws Exception {
    mockMvc
        .perform(get("/api/v1/sos/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Missing or invalid user context"));
  }

  // The gateway named someone whose session is over: 401 with a body, not the chain's bare 401.
  @Test
  void rejectedContextIsAnsweredWithTheSharedErrorShape() throws Exception {
    String loggedOut =
        "{\"user\":{\"uuid\":\""
            + UUID.randomUUID()
            + "\",\"role\":\"USER\",\"otpVerify\":true,\"profileVerify\":true,"
            + "\"isLogout\":true}}";

    mockMvc
        .perform(get("/api/v1/sos/" + UUID.randomUUID()).header("user-context", loggedOut))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Access token is no longer valid"));
  }
}
