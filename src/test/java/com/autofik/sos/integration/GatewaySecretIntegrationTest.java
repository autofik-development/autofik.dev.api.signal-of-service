package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * With the shared secret configured, the real security chain refuses anything that does not carry
 * it, whatever identity it claims, and the identity filter never sees the request.
 */
@TestPropertySource(properties = "sos.gateway.secret=it-secret")
class GatewaySecretIntegrationTest extends FlowTestSupport {

  private HttpResponse<String> list(UUID id, String secret) throws Exception {
    var request = req("/api/v1/sos").header("user-context", userContext(id));
    if (secret != null) {
      request.header("X-Edge-Auth", secret);
    }
    return send(request);
  }

  @Test
  void aRequestWithTheSecret_isServed() throws Exception {
    assertThat(list(UUID.randomUUID(), "it-secret").statusCode()).isEqualTo(200);
  }

  @Test
  void aForgedIdentityWithoutTheSecret_isRefused() throws Exception {
    HttpResponse<String> response = list(UUID.randomUUID(), null);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("gateway credential");
  }

  @Test
  void aWrongSecret_isRefused() throws Exception {
    assertThat(list(UUID.randomUUID(), "nope").statusCode()).isEqualTo(401);
  }

  @Test
  void healthChecks_needNoSecret() throws Exception {
    assertThat(send(req("/healthy")).statusCode()).isEqualTo(200);
  }
}
