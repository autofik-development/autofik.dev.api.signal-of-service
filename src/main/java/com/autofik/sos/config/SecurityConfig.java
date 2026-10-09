package com.autofik.sos.config;

import com.autofik.sos.security.AuthErrorResponder;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.security.GatewaySecretFilter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

  private final GatewayIdentityFilter gatewayIdentityFilter;
  private final AuthErrorResponder errorResponder;

  // Optional so the web-layer test slices, which import this config without the rest of the app,
  // do not need it.
  @Autowired(required = false)
  GatewaySecretFilter gatewaySecretFilter;

  /**
   * Serves the WebSocket test console and lets the handshake fall back to {@code ?clientId=}. See
   * {@link WebSocketConfig} — that fallback lets a caller connect as anyone, so this is a
   * local-development switch and nothing else. Default false; set only in application-dev.yaml.
   */
  @Value("${websocket.dev-insecure-handshake:false}")
  private boolean devInsecureHandshake;

  private final Environment environment;

  /**
   * The flag is read by relaxed binding, so {@code WEBSOCKET_DEV_INSECURE_HANDSHAKE=true} in any
   * environment file would switch it on in test, SIT or prod too. Refuse to start rather than run
   * with a handshake anyone can use to connect as anyone.
   */
  static void requireDevProfile(boolean devInsecureHandshake, Environment environment) {
    if (devInsecureHandshake && !environment.acceptsProfiles(Profiles.of("dev"))) {
      throw new IllegalStateException(
          "websocket.dev-insecure-handshake=true is only allowed with the 'dev' profile. "
              + "Active profiles: "
              + java.util.Arrays.toString(environment.getActiveProfiles())
              + ". Remove the setting (or WEBSOCKET_DEV_INSECURE_HANDSHAKE) from this environment.");
    }
  }

  @PostConstruct
  void warnIfInsecure() {
    requireDevProfile(devInsecureHandshake, environment);
    if (devInsecureHandshake) {
      log.warn(
          "websocket.dev-insecure-handshake=true — the WebSocket handshake accepts a "
              + "caller-supplied ?clientId=, so any client can connect as any user. "
              + "This must never be enabled outside local development.");
    }
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth -> {
              // Health checks are read by the load balancer, which carries no identity.
              auth.requestMatchers("/", "/healthy", "/actuator/**", "/error").permitAll();
              // The upgrade carries the identity headers and is authorised in WebSocketConfig's
              // handshake interceptor, which rejects it there rather than here.
              auth.requestMatchers("/ws/**").permitAll();
              if (devInsecureHandshake) {
                auth.requestMatchers("/test-ws.html", "/OneSignalSDKWorker.js").permitAll();
              }
              auth.anyRequest().authenticated();
            })
        .exceptionHandling(
            ex ->
                ex
                    // Without this the chain answers an anonymous call with a bodiless 403,
                    // which tells the app the caller is not allowed — when in fact the gateway
                    // never named one, and logging in again is exactly what would fix it.
                    .authenticationEntryPoint(
                        (request, response, authException) ->
                            errorResponder.write(
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "Missing or invalid user context"))
                    // A caller who is known but not allowed here. Same body shape, so every auth
                    // failure on this service reads the same way to the app.
                    .accessDeniedHandler(
                        (request, response, deniedException) ->
                            errorResponder.write(
                                response,
                                HttpStatus.FORBIDDEN,
                                "You are not allowed to perform this action")))
        .addFilterBefore(gatewayIdentityFilter, UsernamePasswordAuthenticationFilter.class);
    if (gatewaySecretFilter != null) {
      // Ahead of the identity filter: a request without the secret never has its headers read.
      http.addFilterBefore(gatewaySecretFilter, GatewayIdentityFilter.class);
    }

    return http.build();
  }
}
