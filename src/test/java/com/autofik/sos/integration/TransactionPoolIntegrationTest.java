package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Pins the fix for the connection-pool risk: creating an SOS calls Medusa and Google, which can
 * each take seconds, and that wait must not happen while holding a database connection. Here both
 * are made slow on purpose, many drivers create SOS tickets at once against a small pool, and an
 * unrelated database-backed request is sent in the middle of it.
 *
 * <p>With the old single transaction, the first {@code DB_POOL_MAX} creates would each have taken a
 * connection and sat on it through the sleep, and the unrelated request would have waited for the 2
 * s pool timeout and failed.
 */
class TransactionPoolIntegrationTest extends IntegrationTestBase {

  private static final Duration SLOW_EXTERNAL_CALL = Duration.ofSeconds(3);
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private HttpResponse<String> post(UUID driverId, String body) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sos"))
            .header("user-context", userContext(driverId))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** A request that needs a database connection and nothing else. */
  private int listOwnTickets(UUID driverId, Duration limit) throws Exception {
    return HTTP.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sos"))
                .header("user-context", userContext(driverId))
                .timeout(limit)
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  private static String createBody(Driver d) {
    return "{\"userId\":\""
        + d.user().getUserId()
        + "\",\"serviceGroupId\":\"sg_it\",\"description\":\"flat tyre\","
        + "\"latitude\":11.5564,\"longitude\":104.9282,\"vehicleId\":\""
        + d.vehicle().getId()
        + "\",\"broadcastRadiusKm\":10}";
  }

  @Test
  void slowExternalCalls_doNotHoldDatabaseConnections() throws Exception {
    when(medusaService.resolveServiceGroupKey(any()))
        .thenAnswer(
            inv -> {
              Thread.sleep(SLOW_EXTERNAL_CALL.toMillis());
              return "flat_tire";
            });
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble()))
        .thenAnswer(
            inv -> {
              Thread.sleep(SLOW_EXTERNAL_CALL.toMillis());
              return "Phnom Penh";
            });

    // Three times as many drivers as the pool has connections.
    int creators = DB_POOL_MAX * 3;
    List<Driver> drivers = new ArrayList<>();
    for (int i = 0; i < creators; i++) drivers.add(newDriver());

    List<CompletableFuture<HttpResponse<String>>> creates = new ArrayList<>();
    for (Driver d : drivers) {
      creates.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return post(d.user().getUserId(), createBody(d));
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              }));
    }

    // Give every create time to reach its slow external call.
    Thread.sleep(1_000);

    UUID bystander = UUID.randomUUID();
    for (int probe = 0; probe < 5; probe++) {
      assertThat(listOwnTickets(bystander, Duration.ofSeconds(1)))
          .as(
              "unrelated DB-backed request #%d while %d creates wait on Medusa/Google",
              probe, creators)
          .isEqualTo(200);
      Thread.sleep(300);
    }

    for (CompletableFuture<HttpResponse<String>> f : creates) {
      HttpResponse<String> response = f.get();
      assertThat(response.statusCode()).as("create response: %s", response.body()).isEqualTo(201);
    }
  }
}
