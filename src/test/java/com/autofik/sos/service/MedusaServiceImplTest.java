package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.config.MedusaConfig;
import com.autofik.sos.config.TestTracing;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.service.impl.MedusaServiceImpl;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.persistence.EntityNotFoundException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real RestClient against a throwaway local HTTP server: no mocks of the client itself. */
class MedusaServiceImplTest {

  private HttpServer server;
  private final AtomicReference<String> seenKey = new AtomicReference<>();
  private final AtomicReference<String> seenPath = new AtomicReference<>();
  private final AtomicReference<String> seenTraceparent = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String body = "";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          seenKey.set(ex.getRequestHeaders().getFirst("x-publishable-api-key"));
          seenPath.set(ex.getRequestURI().getRawPath());
          seenTraceparent.set(ex.getRequestHeaders().getFirst("traceparent"));
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

  private MedusaConfig config() {
    MedusaConfig cfg = new MedusaConfig();
    cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    cfg.setPublicKey("pk_unit");
    return cfg;
  }

  private MedusaServiceImpl service() {
    return new MedusaServiceImpl(config());
  }

  // Observed, the call is a client span under the request that made it, and Medusa gets that
  // span's traceparent. The publishable key goes on the request, never on the span.
  @Test
  void observed_isAClientSpan_andSendsItsTraceparent() {
    body = "{\"service_group\":{\"key\":\"tow\"}}";
    try (TestTracing tracing = new TestTracing()) {
      MedusaServiceImpl observed = new MedusaServiceImpl(config(), tracing.registry);

      Observation request = Observation.start("request", tracing.registry);
      try (Observation.Scope ignored = request.openScope()) {
        observed.resolveServiceGroupKey("sg_1");
      } finally {
        request.stop();
      }

      SpanData client =
          tracing.finished().stream()
              .filter(s -> s.getKind() == SpanKind.CLIENT)
              .findFirst()
              .orElseThrow();
      SpanData parent =
          tracing.finished().stream()
              .filter(s -> s.getName().equals("request"))
              .findFirst()
              .orElseThrow();
      assertThat(client.getParentSpanId()).isEqualTo(parent.getSpanId());
      assertThat(seenTraceparent.get())
          .startsWith("00-" + client.getTraceId() + "-" + client.getSpanId() + "-");
      assertThat(client.getAttributes().asMap().values())
          .noneMatch(v -> v.toString().contains("pk_unit"));
    }
  }

  @Test
  void unobserved_sendsNoTraceparent() {
    body = "{\"service_group\":{\"key\":\"tow\"}}";
    service().resolveServiceGroupKey("sg_1");
    assertThat(seenTraceparent.get()).isNull();
  }

  @Test
  void resolvesTheKey_upperCased_andSendsThePublishableKey() {
    body = "{\"service_group\":{\"id\":\"sg_1\",\"key\":\"tow\",\"label\":\"Towing\"}}";

    assertThat(service().resolveServiceGroupKey("sg_1")).isEqualTo("TOW");
    assertThat(seenKey.get()).isEqualTo("pk_unit");
    assertThat(seenPath.get()).isEqualTo("/store/service-groups/sg_1");
  }

  @Test
  void idWithSlashes_isEncodedIntoOneSegment_notTreatedAsAPath() {
    body = "{\"service_group\":{\"key\":\"x\"}}";

    service().resolveServiceGroupKey("../admin/users");

    assertThat(seenPath.get()).doesNotContain("/admin/users").startsWith("/store/service-groups/");
  }

  @Test
  void http404_becomesEntityNotFound() {
    status = 404;
    body = "{\"message\":\"nope\"}";

    assertThatThrownBy(() -> service().resolveServiceGroupKey("missing"))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining("missing");
  }

  @Test
  void emptyServiceGroup_becomesEntityNotFound() {
    body = "{\"service_group\":null}";

    assertThatThrownBy(() -> service().resolveServiceGroupKey("sg"))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void http500_becomesServiceUnavailable() {
    status = 500;
    body = "{\"message\":\"boom\"}";

    assertThatThrownBy(() -> service().resolveServiceGroupKey("sg"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void malformedJson_becomesServiceUnavailable() {
    body = "<html>gateway error</html>";

    assertThatThrownBy(() -> service().resolveServiceGroupKey("sg"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void connectionRefused_becomesServiceUnavailable() {
    MedusaConfig cfg = new MedusaConfig();
    cfg.setBaseUrl("http://127.0.0.1:1");
    cfg.setPublicKey("k");

    assertThatThrownBy(() -> new MedusaServiceImpl(cfg).resolveServiceGroupKey("sg"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  /** A hung Medusa must fail within the read timeout, not hold the SOS-create thread forever. */
  @Test
  void aHungMedusa_failsWithinTheReadTimeout_asServiceUnavailable() throws Exception {
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    server.removeContext("/");
    server.createContext(
        "/",
        ex -> {
          try {
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
          }
          ex.close();
        });
    MedusaConfig cfg = new MedusaConfig();
    cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    cfg.setPublicKey("k");
    cfg.setReadTimeoutMs(300);
    long start = System.nanoTime();

    try {
      assertThatThrownBy(() -> new MedusaServiceImpl(cfg).resolveServiceGroupKey("sg"))
          .isInstanceOf(ServiceUnavailableException.class);
      assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3_000);
    } finally {
      release.countDown();
    }
  }

  @Test
  void defaultTimeouts_areShort() {
    MedusaConfig cfg = new MedusaConfig();
    assertThat(cfg.getConnectTimeoutMs()).isEqualTo(2000);
    assertThat(cfg.getReadTimeoutMs()).isEqualTo(3000);
  }

  @Test
  void nullBody_becomesEntityNotFound() {
    body = "null";

    assertThatThrownBy(() -> service().resolveServiceGroupKey("sg"))
        .isInstanceOf(EntityNotFoundException.class);
  }
}
