package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The identity headers are unsigned, so this secret is what stops a caller who reaches the port
 * from naming themselves. What is waved through decides whether that holds.
 */
class GatewaySecretFilterTest {

  private static final String SECRET = "s3cret-value";
  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final FilterChain chain = mock(FilterChain.class);

  private GatewaySecretFilter filter(String secret) {
    return new GatewaySecretFilter(
        "X-Edge-Auth", secret, new AuthErrorResponder(objectMapper), new MockEnvironment());
  }

  private MockHttpServletResponse call(GatewaySecretFilter filter, String path, String header)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    if (header != null) {
      request.addHeader("X-Edge-Auth", header);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void theRightSecret_passes() throws Exception {
    MockHttpServletResponse response = call(filter(SECRET), "/api/v1/sos", SECRET);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(chain).doFilter(any(), any());
  }

  @Test
  void aSecretWithStrayWhitespace_stillPasses() throws Exception {
    call(filter(SECRET), "/api/v1/sos", "  " + SECRET + " ");

    verify(chain).doFilter(any(), any());
  }

  @Test
  void noSecret_isRefused_andTheChainNeverRuns() throws Exception {
    MockHttpServletResponse response = call(filter(SECRET), "/api/v1/sos", null);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("gateway credential");
    verify(chain, never()).doFilter(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong", "", "s3cret-valu", "s3cret-value-and-more", "S3CRET-VALUE"})
  void aWrongSecret_isRefused(String presented) throws Exception {
    MockHttpServletResponse response = call(filter(SECRET), "/api/v1/sos", presented);

    assertThat(response.getStatus()).isEqualTo(401);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void theRefusalNeverEchoesWhatWasPresented() throws Exception {
    MockHttpServletResponse response = call(filter(SECRET), "/api/v1/sos", "guess-123");

    assertThat(response.getContentAsString()).doesNotContain("guess-123").doesNotContain(SECRET);
  }

  // A forged identity with no secret is exactly the attack: refused before the identity filter.
  @Test
  void aForgedIdentityWithoutTheSecret_isRefused() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sos");
    request.setRequestURI("/api/v1/sos");
    request.addHeader(
        "user-context", "{\"user\":{\"uuid\":\"" + java.util.UUID.randomUUID() + "\"}}");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter(SECRET).doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    verify(chain, never()).doFilter(any(), any());
  }

  // The load balancer carries no secret.
  @ParameterizedTest
  @ValueSource(strings = {"/", "/healthy", "/error", "/actuator", "/actuator/health"})
  void healthChecks_stayOpen(String path) throws Exception {
    call(filter(SECRET), path, null);

    verify(chain).doFilter(any(), any());
  }

  // Only the exact health paths: a look-alike must not slip through.
  @ParameterizedTest
  @ValueSource(strings = {"/healthy/../api/v1/sos", "/healthyx", "/actuatorx", "/ws", "/ws/info"})
  void lookAlikesAndTheUpgrade_stillNeedTheSecret(String path) throws Exception {
    MockHttpServletResponse response = call(filter(SECRET), path, null);

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void aBlankSecret_turnsTheCheckOff(String blank) throws Exception {
    MockHttpServletResponse response = call(filter(blank), "/api/v1/sos", null);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(chain).doFilter(any(), any());
  }
}
