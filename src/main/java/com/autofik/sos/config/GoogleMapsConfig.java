package com.autofik.sos.config;

import com.google.maps.GeoApiContext;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Google Maps Java Client SDK (GeoApiContext). This singleton bean is shared by
 * GoogleMapsServiceImpl for reverse geocoding and distance matrix calculations.
 */
@Configuration
public class GoogleMapsConfig {

  // The ':' after the key name sets an empty string as the default, allowing the app
  // to start without a Maps key configured (geocoding will silently return null).
  @Value("${google.maps.api-key:}")
  private String apiKey;

  // Empty in every real environment, so the SDK talks to Google. The load-test stack points it at a
  // WireMock stub so a slow Distance Matrix call can be reproduced without a key or a bill.
  @Value("${google.maps.base-url:}")
  private String baseUrl;

  // Queries per second the SDK allows itself, and the number of calls it lets run at once.
  @Value("${google.maps.query-rate-limit:50}")
  private int queryRateLimit;

  @Bean
  public GeoApiContext geoApiContext() {
    GeoApiContext.Builder builder =
        new GeoApiContext.Builder()
            .apiKey(apiKey)
            .connectTimeout(2, TimeUnit.SECONDS) // Max wait to open TCP connection to Google
            .readTimeout(5, TimeUnit.SECONDS); // Max wait to receive the full API response
    if (!baseUrl.isBlank()) {
      builder.baseUrlOverride(baseUrl);
    }
    // Without this the SDK's HTTP client allows only 5 requests in flight to Google at once
    // (OkHttp's
    // per-host default; the dispatcher limits are only raised by this call). The SDK's own default
    // rate of 50 queries per second is unchanged, but concurrency was capped far below it: with a
    // slow Google answer every bid, ETA refresh and geocode queued behind 5 calls.
    builder.queryRateLimit(queryRateLimit);
    return builder.build();
  }
}
