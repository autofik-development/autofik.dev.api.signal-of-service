package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.google.maps.DistanceMatrixApi;
import com.google.maps.GeoApiContext;
import com.google.maps.model.LatLng;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The base URL is empty everywhere but the load-test stack; both shapes must build the bean. */
class GoogleMapsConfigTest {

  private GoogleMapsConfig config(String baseUrl) {
    GoogleMapsConfig config = new GoogleMapsConfig();
    ReflectionTestUtils.setField(config, "apiKey", "AIzaTestKey");
    ReflectionTestUtils.setField(config, "baseUrl", baseUrl);
    ReflectionTestUtils.setField(config, "queryRateLimit", 50);
    return config;
  }

  @Test
  void blankBaseUrl_buildsTheDefaultContext() {
    assertThatCode(() -> config("").geoApiContext().shutdown()).doesNotThrowAnyException();
  }

  /** The point of the override: a Distance Matrix request goes to our server, not to Google. */
  @Test
  void baseUrlOverride_sendsRequestsToThatServer() throws Exception {
    AtomicReference<String> path = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          byte[] body =
              "{\"status\":\"OK\",\"rows\":[{\"elements\":[{\"status\":\"OK\",\"distance\":{\"text\":\"1 km\",\"value\":1000},\"duration\":{\"text\":\"1 min\",\"value\":60}}]}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    GeoApiContext context =
        config("http://127.0.0.1:" + server.getAddress().getPort()).geoApiContext();
    try {
      DistanceMatrixApi.newRequest(context)
          .origins(new LatLng(11.5, 104.9))
          .destinations(new LatLng(11.6, 105.0))
          .await();
      assertThat(path.get()).isEqualTo("/maps/api/distancematrix/json");
    } finally {
      context.shutdown();
      server.stop(0);
    }
  }
}
