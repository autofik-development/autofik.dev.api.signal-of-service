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
  private final AtomicReference<String> seenQuery = new AtomicReference<>();
  private final AtomicReference<String> seenRequestBody = new AtomicReference<>();
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
          seenQuery.set(ex.getRequestURI().getRawQuery());
          seenRequestBody.set(
              new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
    cfg.setConsumePath("/internal/billings/tokens/consume");
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
    assertThat(seenMethodAndPath.get()).isEqualTo("GET /internal/billings/verify");
    assertThat(seenQuery.get()).isEqualTo("userId=" + id + "&userRole=CUSTOMER");
  }

  @Test
  void aMissingRole_isUnavailable_andBillingIsNeverCalled() {
    assertThatThrownBy(() -> client().verify(id, null))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(seenMethodAndPath.get()).isNull();
  }

  @Test
  void aNoPaidPlanAnswer_meansNoPlan() {
    body =
        """
        {"status":"success","code":"BILLING_VERIFY_SUCCESS",
         "data":{"userId":"11cf5fc5-20dc-44c8-a346-6dd3b323dd66","billingId":null,"planId":null,
                 "tier":null,"userRole":"USER","tokenBalance":0,"expired":false,"canUse":false}}
        """;
    assertThatThrownBy(() -> client().verify(id, "user"))
        .isInstanceOfSatisfying(
            PlanRequiredException.class,
            e -> assertThat(e.reason()).isEqualTo(PlanRequiredException.Reason.NO_PLAN));
  }

  @Test
  void a404_meansNoPlan() {
    status = 404;
    body = "{}";
    assertThatThrownBy(() -> client().verify(id, "user"))
        .isInstanceOfSatisfying(
            PlanRequiredException.class,
            e -> assertThat(e.reason()).isEqualTo(PlanRequiredException.Reason.NO_PLAN));
  }

  @Test
  void aServerError_isUnavailable() {
    status = 500;
    body = "boom";
    assertThatThrownBy(() -> client().verify(id, "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anotherClientError_isUnavailable_notTheCustomersFault() {
    status = 401;
    body = "{}";
    assertThatThrownBy(() -> client().verify(id, "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void aBodyWithoutData_isUnavailable() {
    body = "{\"status\":\"success\"}";
    assertThatThrownBy(() -> client().verify(id, "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anEmptyBody_isUnavailable() {
    body = "";
    assertThatThrownBy(() -> client().verify(id, "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void notConfigured_isUnavailable() {
    assertThatThrownBy(() -> new BillingClientImpl(new BillingConfig()).verify(id, "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anUnreachableBilling_isUnavailable() {
    BillingConfig cfg = new BillingConfig();
    cfg.setBaseUrl("http://127.0.0.1:1");
    cfg.setServiceKey("k");
    cfg.setVerifyPath("/internal/billings/verify");
    cfg.setConsumePath("/internal/billings/tokens/consume");
    cfg.setConnectTimeoutMs(300);
    cfg.setReadTimeoutMs(300);
    assertThatThrownBy(() -> new BillingClientImpl(cfg).verify(id, "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  private static final String CUT =
      """
      {"status":"success","code":"TOKEN_CONSUME_SUCCESS",
       "data":{"userId":"11cf5fc5-20dc-44c8-a346-6dd3b323dd66","billingId":"6aeb",
               "userRole":"USER","requestId":"sos-1","consumed":1,"tokenBalance":399,
               "alreadyProcessed":false}}
      """;

  private PlanRequiredException.Reason consumeRefusedAs(int code, String message) {
    status = code;
    body = "{\"status\":\"error\",\"message\":\"" + message + "\"}";
    try {
      client().consume(id, "sos-1", "user");
    } catch (PlanRequiredException e) {
      return e.reason();
    }
    return null;
  }

  @Test
  void consume_postsTheThreeFields_andReturnsTheData() {
    body = CUT;

    var data = client().consume(id, "sos-1", "user");

    assertThat(data.consumed()).isEqualTo(1);
    assertThat(data.tokenBalance()).isEqualTo(399);
    assertThat(data.alreadyProcessed()).isFalse();
    assertThat(seenKey.get()).isEqualTo("key_unit");
    assertThat(seenMethodAndPath.get()).isEqualTo("POST /internal/billings/tokens/consume");
    assertThat(seenRequestBody.get())
        .contains("\"userId\":\"" + id + "\"")
        .contains("\"requestId\":\"sos-1\"")
        .contains("\"userRole\":\"user\"");
  }

  @Test
  void consume_aRepeatedRequestId_isASuccess() {
    body = CUT.replace("\"alreadyProcessed\":false", "\"alreadyProcessed\":true");
    assertThat(client().consume(id, "sos-1", "user").alreadyProcessed()).isTrue();
  }

  @Test
  void consume_definiteRefusals_mapToThePlanReasons() {
    assertThat(consumeRefusedAs(404, "No active billing for this user"))
        .isEqualTo(PlanRequiredException.Reason.NO_PLAN);
    assertThat(consumeRefusedAs(403, "Plan has expired"))
        .isEqualTo(PlanRequiredException.Reason.EXPIRED);
    assertThat(consumeRefusedAs(409, "No tokens left"))
        .isEqualTo(PlanRequiredException.Reason.NO_TOKENS);
  }

  @Test
  void consume_everythingElse_isUnavailable_soTheCallerRetries() {
    for (int[] code : new int[][] {{409}, {403}, {401}, {400}, {500}}) {
      status = code[0];
      body = "{\"message\":\"requestId is already being processed\"}";
      assertThatThrownBy(() -> client().consume(id, "sos-1", "user"))
          .isInstanceOf(ServiceUnavailableException.class);
    }
  }

  @Test
  void consume_notConfigured_isUnavailable() {
    assertThatThrownBy(
            () -> new BillingClientImpl(new BillingConfig()).consume(id, "sos-1", "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void consume_aBodyWithoutData_isUnavailable() {
    body = "{\"status\":\"success\"}";
    assertThatThrownBy(() -> client().consume(id, "sos-1", "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void consume_anUnreachableBilling_isUnavailable() {
    BillingConfig cfg = new BillingConfig();
    cfg.setBaseUrl("http://127.0.0.1:1");
    cfg.setServiceKey("k");
    cfg.setConsumePath("/internal/billings/tokens/consume");
    cfg.setConnectTimeoutMs(300);
    cfg.setReadTimeoutMs(300);
    assertThatThrownBy(() -> new BillingClientImpl(cfg).consume(id, "sos-1", "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void aBlankBaseUrl_meansNotConfigured() {
    BillingConfig cfg = new BillingConfig();
    cfg.setBaseUrl("  ");
    assertThatThrownBy(() -> new BillingClientImpl(cfg).verify(id, "user"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void aBlankRole_isUnavailable_andBillingIsNeverCalled() {
    assertThatThrownBy(() -> client().verify(id, "  "))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(seenMethodAndPath.get()).isNull();
  }

  @Test
  void aPlanWithoutABillingId_butUsable_isNotTreatedAsNoPlan() {
    body = OK.replace("\"billingId\":\"6aeb\"", "\"billingId\":null");
    assertThat(client().verify(id, "user").canUse()).isTrue();
  }
}
