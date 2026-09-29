package com.autofik.sos.config;

import com.autofik.sos.middleware.AuthMiddleware;
import com.autofik.sos.security.AuthMechanicArgumentResolver;
import com.autofik.sos.security.AuthUserArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS policy for all REST endpoints under /api/**. Allows the mobile app and web dashboard
 * to call the API from any origin during development. Tighten allowedOriginPatterns to specific
 * domains before going to production.
 *
 * <p>Also wires the request-scoped auth layer: which routes require a caller, and how handlers ask
 * for one.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

  private final AuthMiddleware authMiddleware;
  private final AuthUserArgumentResolver authUserArgumentResolver;
  private final AuthMechanicArgumentResolver authMechanicArgumentResolver;

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

  /**
   * Every endpoint under /api/** acts for a specific driver or mechanic, so every one of them needs
   * a caller. The health endpoints are read by the load balancer, which carries no identity, and
   * are not under this prefix to begin with.
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authMiddleware).addPathPatterns("/api/**");
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(authUserArgumentResolver);
    resolvers.add(authMechanicArgumentResolver);
  }
}
