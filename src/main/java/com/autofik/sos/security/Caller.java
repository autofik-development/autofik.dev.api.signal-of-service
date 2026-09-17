package com.autofik.sos.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Access to the identity {@link GatewayIdentityFilter} resolved for the current request.
 *
 * <p>Controllers normally take {@code @AuthenticationPrincipal UUID} directly; this is for the
 * places that cannot — a service that needs the caller's bearer or role, or a legacy request field
 * that has to be checked against the real identity before it is deleted next release.
 */
public final class Caller {

  private Caller() {}

  /**
   * Rejects a {@code userId} / {@code mechanicId} still being sent in a body or query string when
   * it disagrees with the proven identity. The claimed value is never acted on either way.
   */
  public static void mustMatch(UUID caller, UUID claimed) {
    if (caller == null || (claimed != null && !claimed.equals(caller))) {
      throw new AccessDeniedException("Identity mismatch");
    }
  }

  /**
   * The full context behind this request — role, device, calling service — as the gateway reported
   * it. Empty on the unauthenticated paths (health checks, the WebSocket upgrade) and on a request
   * that arrived through the pre-{@code user-context} headers, which carry only an id.
   */
  public static Optional<CurrentUser> current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    Object details = auth == null ? null : auth.getDetails();
    return details instanceof CurrentUser caller ? Optional.of(caller) : Optional.empty();
  }

  /** The caller's id, or empty when the request is anonymous. */
  public static Optional<UUID> id() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    Object principal = auth == null ? null : auth.getPrincipal();
    return principal instanceof UUID id ? Optional.of(id) : Optional.empty();
  }

  /**
   * How this service treats the caller, for the branches that have to know without the extra
   * round-trip an id-only lookup would cost.
   *
   * @throws AccessDeniedException when the request is anonymous — a caller with no role is not a
   *     caller any of these branches should be running for.
   */
  public static CallerRole role() {
    return current()
        .map(CurrentUser::role)
        .orElseThrow(() -> new AccessDeniedException("No caller role available"));
  }

  /**
   * The caller's SSO bearer, for the profile and vehicle reads that SSO scopes to its owner.
   *
   * @throws AccessDeniedException when the request carried no token — those reads cannot be retried
   *     later from a background job, so failing here is the only honest answer.
   */
  public static String token() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    Object token = auth == null ? null : auth.getCredentials();
    if (token == null) {
      throw new AccessDeniedException("No caller token available for SSO");
    }
    return (String) token;
  }
}
