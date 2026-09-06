package com.autofik.sos.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS policy for all REST endpoints under /api/**. Allows the mobile app and web dashboard
 * to call the API from any origin during development. Tighten allowedOriginPatterns to specific
 * domains before going to production.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        // allowedOriginPatterns("*") is used instead of allowedOrigins("*") because
        // Spring Security requires patterns (not the wildcard literal) when credentials are
        // involved.
        .allowedOriginPatterns("*")
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        // Browsers hide non-standard headers from JS by default; expose Authorization
        // so the frontend can read it from responses (e.g., refreshed tokens).
        .exposedHeaders("Authorization")
        // Browser caches the preflight OPTIONS response for 1 hour to reduce repeated checks
        .maxAge(3600);
  }
}
