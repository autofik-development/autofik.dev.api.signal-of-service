package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.maps.DistanceMatrixApi;
import com.google.maps.GeoApiContext;
import com.google.maps.model.LatLng;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * How many Google calls can be in flight at once. The SDK's HTTP client allows only 5 concurrent
 * requests per host unless a query rate limit is set, so a slow Google answer turned every extra
 * caller into a queue entry (found by the bidding load test: about 3 bids per second whatever the
 * thread pools allowed).
 */
class GoogleMapsConcurrencyTest {

  private static final String OK =
      "{\"status\":\"OK\",\"rows\":[{\"elements\":[{\"status\":\"OK\",\"distance\":{\"text\":\"1 km\",\"value\":1000},\"duration\":{\"text\":\"1 min\",\"value\":60}}]}]}";

  /** Fires 30 calls at a server that takes 300 ms each; returns the most it saw at once. */
  private int peakInFlight(GeoApiContext context, HttpServer server, AtomicInteger peak)
      throws Exception {
    ExecutorService callers = Executors.newFixedThreadPool(30);
    try {
      List<Future<?>> done = new ArrayList<>();
      for (int i = 0; i < 30; i++) {
        done.add(
            callers.submit(
                (Callable<Object>)
                    () ->
                        DistanceMatrixApi.newRequest(context)
                            .origins(new LatLng(11.5, 104.9))
                            .destinations(new LatLng(11.6, 105.0))
                            .await()));
      }
      for (Future<?> f : done) f.get();
      return peak.get();
    } finally {
      callers.shutdownNow();
      context.shutdown();
      server.stop(0);
    }
  }

  private HttpServer slowServer(AtomicInteger peak) throws Exception {
    AtomicInteger inFlight = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/",
        exchange -> {
          peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
          try {
            Thread.sleep(300);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          inFlight.decrementAndGet();
          byte[] body = OK.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private GeoApiContext context(HttpServer server, int rateLimit) {
    GoogleMapsConfig config = new GoogleMapsConfig();
    ReflectionTestUtils.setField(config, "apiKey", "AIzaTestKey");
    ReflectionTestUtils.setField(
        config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
    ReflectionTestUtils.setField(config, "queryRateLimit", rateLimit);
    return config.geoApiContext();
  }

  @Test
  void withTheConfiguredLimit_manyCallsRunAtOnce() throws Exception {
    AtomicInteger peak = new AtomicInteger();
    HttpServer server = slowServer(peak);

    int seen = peakInFlight(context(server, 50), server, peak);

    assertThat(seen).as("peak concurrent Google calls").isGreaterThan(10);
  }

  /** Documents the cause: the SDK left as it ships never has more than 5 calls in flight. */
  @Test
  void withoutTheSetting_theSdkCapsAtFiveInFlight() throws Exception {
    AtomicInteger peak = new AtomicInteger();
    HttpServer server = slowServer(peak);
    GeoApiContext plain =
        new GeoApiContext.Builder()
            .apiKey("AIzaTestKey")
            .baseUrlOverride("http://127.0.0.1:" + server.getAddress().getPort())
            .build();

    int seen = peakInFlight(plain, server, peak);

    assertThat(seen).as("peak concurrent Google calls").isLessThanOrEqualTo(5);
  }
}
