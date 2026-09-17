package com.autofik.sos.security;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves the caller's identity from the {@code user-context} header the gateway sets on every
 * request it proxies here, instead of accepting a userId/mechanicId from the client.
 *
 * <p>The gateway only sets this header after its own verification has already checked the caller's
 * bearer token — otp and profile verified, session active, not logged out — so a present,
 * well-formed header is trusted. A missing or malformed one means either the gateway is
 * misconfigured or a caller is bypassing it entirely, and in both cases the caller stays anonymous
 * and the filter chain answers 401.
 *
 * <p>This is only as strong as the network policy that keeps anything but the gateway off this
 * service: the header carries no signature of its own.
 */
@Component
@RequiredArgsConstructor
public class MicroserviceTokenSdk {

  /** Statuses the gateway reports for a session that is no longer usable. */
  private static final Set<String> INVALID_TOKEN_STATUSES = Set.of("unauth", "expired", "failed");

  private final ObjectMapper objectMapper;

  /**
   * The identity behind this request, or empty when the request carried no context at all — a
   * health check, or a caller the chain will answer 401 for.
   *
   * @param headers case-insensitive header lookup for the request being handled
   * @throws UserContextRejectedException when a context arrived but names a session or a role this
   *     service will not act on
   */
  public Optional<CurrentUser> resolveCurrentUser(UnaryOperator<String> headers) {
    AppHeaderContext context = AppHeaderContext.from(headers, objectMapper);
    UserContext.User user = context.user();

    if (user == null) {
      return resolveLegacyHeaders(headers);
    }

    UUID id = parseUuid(user.uuid());
    if (id == null) {
      // The gateway named nobody. Treat it as no context rather than a rejection, so the
      // chain's own 401 stands and public routes keep working.
      return Optional.empty();
    }

    if (user.loggedOut() || isInvalidTokenStatus(user.tokenStatus())) {
      throw UserContextRejectedException.unauthorized("Access token is no longer valid");
    }

    if (!user.otpVerified() || !user.profileVerified()) {
      throw UserContextRejectedException.forbidden("User profile is not fully verified");
    }

    CallerRole role =
        CallerRole.fromSsoRole(user.role())
            .orElseThrow(
                () ->
                    UserContextRejectedException.forbidden(
                        "Role '" + user.role() + "' is not permitted to use this service"));

    return Optional.of(new CurrentUser(id, role, user.deviceId(), context.serviceCode()));
  }

  /**
   * The identity headers APISIX wrote before {@code user-context} existed.
   *
   * <p>ponytail: drop this once every environment's gateway sends {@code user-context}. Until then
   * dropping it would lock out whatever is still routed the old way, and a mechanic mid-job is not
   * who should discover that.
   *
   * <p>Exactly one of the two must be present. Both means the edge is misconfigured and the request
   * is left anonymous rather than guessed at — neither identity is more trustworthy than the other.
   */
  private Optional<CurrentUser> resolveLegacyHeaders(UnaryOperator<String> headers) {
    String user = headers.apply(GatewayIdentityFilter.USER_HEADER);
    String mechanic = headers.apply(GatewayIdentityFilter.MECHANIC_HEADER);

    if ((user == null) == (mechanic == null)) {
      return Optional.empty();
    }

    UUID id = parseUuid(user != null ? user : mechanic);
    if (id == null) {
      return Optional.empty();
    }

    CallerRole role = user != null ? CallerRole.USER : CallerRole.MECHANIC;
    return Optional.of(new CurrentUser(id, role, null, null));
  }

  private static boolean isInvalidTokenStatus(String tokenStatus) {
    return tokenStatus != null
        && INVALID_TOKEN_STATUSES.contains(tokenStatus.trim().toLowerCase(Locale.ROOT));
  }

  private static UUID parseUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException malformedUuid) {
      return null;
    }
  }
}
