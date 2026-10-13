package com.autofik.sos.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses any request that does not carry the secret only the internal gateway knows.
 *
 * <p>{@code user-context} and {@code mechanic-context} carry no signature, and SOS acts on them
 * completely: who the caller is, and the profile it writes to its own tables. Network policy (the
 * SOS port reachable only from the gateway) is the first line of defence; this is the second, so a
 * leaked port or a mis-set security group does not let anyone act as anyone.
 *
 * <p>Off when {@code sos.gateway.secret} is blank, so a laptop and the test suite need no setup.
 * Under the {@code prod} profile a blank secret stops the service from starting, because it would
 * then trust headers from anything that can reach it.
 *
 * <p>Health checks stay open: the load balancer carries no secret.
 */
@Component
@Slf4j
public class GatewaySecretFilter extends OncePerRequestFilter {

  private final String headerName;
  private final byte[] secret;
  private final AuthErrorResponder errorResponder;
  private final Environment environment;

  public GatewaySecretFilter(
      @Value("${sos.gateway.header:X-Edge-Auth}") String headerName,
      @Value("${sos.gateway.secret:}") String secret,
      AuthErrorResponder errorResponder,
      Environment environment) {
    this.headerName = headerName;
    this.secret = secret.isBlank() ? null : secret.trim().getBytes(StandardCharsets.UTF_8);
    this.errorResponder = errorResponder;
    this.environment = environment;
  }

  @PostConstruct
  void announce() {
    if (secret != null) {
      log.info("Gateway shared secret required on every request except health checks");
    } else if (environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "sos.gateway.secret is not set: under the prod profile user-context and mechanic-context"
              + " would be trusted from anything that can reach this port. Set SOS_GATEWAY_SECRET.");
    } else {
      log.warn("sos.gateway.secret is not set: the gateway shared secret is not being checked");
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (secret == null) {
      return true;
    }
    String path = request.getRequestURI();
    return path.equals("/")
        || path.equals("/healthy")
        || path.equals("/error")
        || path.equals("/actuator")
        || path.startsWith("/actuator/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String presented = request.getHeader(headerName);
    // Constant time, so the secret cannot be guessed a byte at a time from response timing.
    if (presented != null
        && MessageDigest.isEqual(presented.trim().getBytes(StandardCharsets.UTF_8), secret)) {
      chain.doFilter(request, response);
      return;
    }
    // Names the request, never the value that was presented.
    log.warn(
        "Rejected {} {} without the gateway secret (ip={})",
        request.getMethod(),
        request.getRequestURI(),
        request.getRemoteAddr());
    errorResponder.write(
        response, HttpStatus.UNAUTHORIZED, "Missing or invalid gateway credential");
  }
}
