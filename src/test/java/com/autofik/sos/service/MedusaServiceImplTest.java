package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.config.MedusaConfig;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.service.impl.MedusaServiceImpl;
import com.sun.net.httpserver.HttpServer;
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

  private MedusaServiceImpl service() {
    MedusaConfig cfg = new MedusaConfig();
    cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    cfg.setPublicKey("pk_unit");
    return new MedusaServiceImpl(cfg);
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
}
