package com.autofik.sos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Installs the acting identity {@link MicroserviceTokenSdk} resolved from the gateway's headers,
 * and nothing else.
 *
 * <p>The caller must never be able to name themselves. Every controller reads the principal this
 * filter installs instead of a {@code userId} / {@code mechanicId} parameter, because a parameter
 * is chosen by whoever sends the request.
 *
 * <p>A request with no context passes through anonymous, so health checks and the WebSocket upgrade
 * still work and the chain answers 401 for everything else. A context that arrived but cannot be
 * acted on is answered here instead, because 401 and 403 mean different things to the app and the
 * chain's blanket 401 would hide the difference.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GatewayIdentityFilter extends OncePerRequestFilter {

  /** ponytail: pre-{@code user-context} headers — see MicroserviceTokenSdk#resolveLegacyHeaders. */
  public static final String USER_HEADER = "X-User-Id";

  public static final String MECHANIC_HEADER = "X-Mechanic-Id";

  /** Kept as aliases so callers and tests do not have to know about {@link CallerRole}. */
  public static final String ROLE_USER = CallerRole.USER.authority();

  public static final String ROLE_MECHANIC = CallerRole.MECHANIC.authority();

  private final MicroserviceTokenSdk tokenSdk;
  private final ObjectMapper objectMapper;
  private final AuthErrorResponder errorResponder;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<CurrentUser> caller;
    try {
      caller = tokenSdk.resolveCurrentUser(request::getHeader);
    } catch (UserContextRejectedException rejected) {
      logRejection(request, rejected);
      errorResponder.write(response, rejected.status(), rejected.getMessage());
      return;
    }

    caller.ifPresent(user -> authenticate(user, request));
    chain.doFilter(request, response);
  }

  private void authenticate(CurrentUser caller, HttpServletRequest request) {
    // The bearer goes in the credentials slot. ponytail: unused now that SsoProjection
    // does local-only lookups (no SSO round-trip) — kept for when that call comes back.
    // Never log this value.
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            caller.id(),
            request.getHeader(HttpHeaders.AUTHORIZATION),
            List.of(new SimpleGrantedAuthority(caller.authority())));
    // Everything past this filter reads role/device/serviceCode from here rather than re-reading
    // the headers, which are gone by the time a service or a Kafka listener wants them.
    auth.setDetails(caller);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  /**
   * Names the caller's app and address, because the two rejections worth chasing look alike in a
   * count: a release sending a stale context, and something reaching this service around the
   * gateway. Never logs the context itself — it names the user and their device.
   */
  private void logRejection(HttpServletRequest request, UserContextRejectedException rejected) {
    AppHeaderContext headers = AppHeaderContext.from(request::getHeader, objectMapper);
    String ip = headers.ipAddress() != null ? headers.ipAddress() : request.getRemoteAddr();
    log.warn(
        "Rejected {} {} with {}: {} (service={} userAgent={} ip={})",
        request.getMethod(),
        request.getRequestURI(),
        rejected.status().value(),
        rejected.getMessage(),
        headers.serviceCode(),
        headers.userAgent(),
        ip);
  }
}
