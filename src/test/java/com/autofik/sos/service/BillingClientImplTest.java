package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.dto.external.BillingVerifyResponse;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.service.impl.BillingClientImpl;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real RestClient against a throwaway local HTTP server. */
class BillingClientImplTest {

  private static final String OK =
      """
      {"status":"success","message":"Billing verified successfully","code":"BILLING_VERIFY_SUCCESS",
       "data":{"userId":"11cf5fc5-20dc-44c8-a346-6dd3b323dd66","billingId":"6aeb","planId":"plan_pro",
               "tier":"Pro","tokenBalance":3,"expired":false,"canUse":true,"extra":"ignored"}}
      """;

  private HttpServer server;
  private final AtomicReference<String> seenKey = new AtomicReference<>();
  private final AtomicReference<String> seenBody = new AtomicReference<>();
  private final AtomicReference<String> seenMethodAndPath = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String body = OK;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          seenKey.set(ex.getRequestHeaders().getFirst("X-Service-Key"));
          seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          seenMethodAndPath.set(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath());
          byte[] out = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
          if (out.length > 0) ex.getResponseBody().write(out);
          ex.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private BillingClientImpl client() {
    BillingConfig cfg = new BillingConfig();
    cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    cfg.setServiceKey("key_unit");
    cfg.setVerifyPath("/internal/billings/verify");
    cfg.setConnectTimeoutMs(2000);
    cfg.setReadTimeoutMs(3000);
    return new BillingClientImpl(cfg);
  }

  private final UUID id = UUID.fromString("11cf5fc5-20dc-44c8-a346-6dd3b323dd66");

  @Test
  void success_returnsTheDataBlock_andSendsKeyUserAndRole() {
    BillingVerifyResponse.Data data = client().verify(id, "CUSTOMER");

    assertThat(data.tier()).isEqualTo("Pro");
    assertThat(data.tokenBalance()).isEqualTo(3);
    assertThat(data.expired()).isFalse();
    assertThat(data.canUse()).isTrue();
    assertThat(seenKey.get()).isEqualTo("key_unit");
    assertThat(seenMethodAndPath.get()).isEqualTo("POST /internal/billings/verify");
    assertThat(seenBody.get()).contains(id.toString()).contains("\"role\":\"CUSTOMER\"");
  }

  @Test
  void aMissingRole_isLeftOutOfTheBody() {
    client().verify(id, null);
    assertThat(seenBody.get()).doesNotContain("role");
  }

  @Test
  void a404_meansNoPlan() {
    status = 404;
    body = "{}";
    assertThatThrownBy(() -> client().verify(id, null))
        .isInstanceOfSatisfying(
            PlanRequiredException.class,
            e -> assertThat(e.reason()).isEqualTo(PlanRequiredException.Reason.NO_PLAN));
  }

  @Test
  void aServerError_isUnavailable() {
    status = 500;
    body = "boom";
    assertThatThrownBy(() -> client().verify(id, null))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anotherClientError_isUnavailable_notTheCustomersFault() {
    status = 401;
    body = "{}";
    assertThatThrownBy(() -> client().verify(id, null))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void aBodyWithoutData_isUnavailable() {
    body = "{\"status\":\"success\"}";
    assertThatThrownBy(() -> client().verify(id, null))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anEmptyBody_isUnavailable() {
    body = "";
    assertThatThrownBy(() -> client().verify(id, null))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void notConfigured_isUnavailable() {
    assertThatThrownBy(() -> new BillingClientImpl(new BillingConfig()).verify(id, null))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anUnreachableBilling_isUnavailable() {
    BillingConfig cfg = new BillingConfig();
    cfg.setBaseUrl("http://127.0.0.1:1");
    cfg.setServiceKey("k");
    cfg.setVerifyPath("/internal/billings/verify");
    cfg.setConnectTimeoutMs(300);
    cfg.setReadTimeoutMs(300);
    assertThatThrownBy(() -> new BillingClientImpl(cfg).verify(id, null))
        .isInstanceOf(ServiceUnavailableException.class);
  }
}
