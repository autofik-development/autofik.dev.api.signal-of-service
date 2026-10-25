package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** Inputs a client controls and the service must bound: sort property, page size, text length. */
class RequestLimitsIntegrationTest extends FlowTestSupport {

  private HttpResponse<String> list(Driver d, String query) throws Exception {
    return send(
        req("/api/v1/sos" + query).header("user-context", userContext(d.user().getUserId())).GET());
  }

  @Test
  void anUnknownSortProperty_isABadRequest_notAServerError() throws Exception {
    Driver d = newDriver();

    HttpResponse<String> r = list(d, "?sort=noSuchProperty,desc");

    finding("GET /sos?sort=noSuchProperty answered " + r.statusCode() + ": " + r.body());
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(r.body()).doesNotContain("SosRequest");
  }

  @Test
  void aHugePageSize_isCapped() throws Exception {
    Driver d = newDriver();

    HttpResponse<String> r = list(d, "?size=100000");

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(r.body()).get("size").asInt()).isEqualTo(100);
  }

  @Test
  void aDescriptionOverTheLimit_isRefused() throws Exception {
    Driver d = newDriver();
    String body = createBody(d).replace("flat tyre", "x".repeat(1001));

    HttpResponse<String> r =
        send(
            req("/api/v1/sos")
                .header("user-context", userContext(d.user().getUserId()))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)));

    assertThat(r.statusCode()).isEqualTo(400);
  }
}
