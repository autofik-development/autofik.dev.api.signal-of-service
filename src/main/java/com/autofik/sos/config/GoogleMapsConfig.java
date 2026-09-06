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

  @Bean
  public GeoApiContext geoApiContext() {
    return new GeoApiContext.Builder()
        .apiKey(apiKey)
        .connectTimeout(2, TimeUnit.SECONDS) // Max wait to open TCP connection to Google's servers
        .readTimeout(5, TimeUnit.SECONDS) // Max wait to receive the full API response
        .build();
  }
}
