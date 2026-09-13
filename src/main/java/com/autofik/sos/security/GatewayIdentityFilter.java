package com.autofik.sos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the acting identity from the headers APISIX writes, and nothing else.
 *
 * <p>The caller must never be able to name themselves. Every controller reads the principal this
 * filter installs instead of a {@code userId} / {@code mechanicId} parameter, because a parameter
 * is chosen by whoever sends the request.
 *
 * <p>Exactly one of the two headers must be present. Both means the edge is misconfigured and the
 * request is rejected rather than guessed at — neither identity is more trustworthy than the other.
 *
 * <p>This is only as strong as the network policy that keeps anything but APISIX off the gateway:
 * the headers carry no signature of their own.
 */
@Component
public class GatewayIdentityFilter extends OncePerRequestFilter {

  public static final String USER_HEADER = "X-User-Id";
  public static final String MECHANIC_HEADER = "X-Mechanic-Id";
  public static final String ROLE_USER = "ROLE_USER";
  public static final String ROLE_MECHANIC = "ROLE_MECHANIC";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String user = request.getHeader(USER_HEADER);
    String mechanic = request.getHeader(MECHANIC_HEADER);

    // Exactly one, or the request stays unauthenticated and the filter chain answers 401.
    if ((user == null) != (mechanic == null)) {
      try {
        UUID id = UUID.fromString(user != null ? user : mechanic);
        String role = user != null ? ROLE_USER : ROLE_MECHANIC;
        // The bearer goes in the credentials slot. ponytail: unused now that SsoProjection
        // does local-only lookups (no SSO round-trip) — kept for when that call comes back.
        // Never log this value.
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(
                id,
                request.getHeader(HttpHeaders.AUTHORIZATION),
                List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
      } catch (IllegalArgumentException malformedUuid) {
        // Leave the context empty — a header we cannot parse is a header we do not trust.
      }
    }

    chain.doFilter(request, response);
  }
}
