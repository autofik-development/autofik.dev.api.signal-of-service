package com.autofik.sos.security;

import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves the caller's identity from the {@code user-context} / {@code mechanic-context} header
 * the gateway sets on every request it proxies here, instead of accepting a userId/mechanicId from
 * the client.
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

  private final ObjectMapper objectMapper;

  /**
   * The identity behind this request, or empty when the request carried no context at all — a
   * health check, or a caller the chain will answer 401 for.
   *
   * @param headers case-insensitive header lookup for the request being handled
   * @throws UserContextRejectedException when a context arrived but names a role this service will
   *     not act on
   */
  public Optional<CurrentUser> resolveCurrentUser(UnaryOperator<String> headers) {
    AppHeaderContext context = AppHeaderContext.from(headers, objectMapper);
    ContextIdentity identity = context.identity();

    if (identity == null) {
      // The gateway sends user-context or mechanic-context and nothing else. No context is no
      // caller — there is no second header to fall back to.
      return Optional.empty();
    }

    UUID id = parseUuid(identity.uuid());
    if (id == null) {
      // The gateway named nobody. Treat it as no context rather than a rejection, so the
      // chain's own 401 stands and public routes keep working.
      return Optional.empty();
    }

    // Session state (logged out, expired token) and otpVerify / profileVerify are not checked
    // here: the internal gateway verifies the token and refuses such a caller before forwarding, so
    // a second check would only repeat it.

    return Optional.of(
        new CurrentUser(id, resolveRole(identity), identity.deviceId(), context.serviceCode()));
  }

  /**
   * The role this service will act under, from the role string and from which block carried it.
   *
   * <p>A {@code mechanic-context} that names a driver role is not a disagreement to resolve by
   * preferring one side: the edge is sending something it does not mean, and guessing which half is
   * the mistake is how a driver ends up holding mechanic endpoints.
   */
  private static CallerRole resolveRole(ContextIdentity identity) {
    Optional<CallerRole> declared = CallerRole.fromSsoRole(identity.role());
    Optional<CallerRole> implied = identity.impliedRole();

    if (declared.isEmpty()) {
      return implied.orElseThrow(
          () ->
              UserContextRejectedException.forbidden(
                  "Role '" + identity.role() + "' is not permitted to use this service"));
    }
    if (implied.isPresent() && implied.get() != declared.get()) {
      throw UserContextRejectedException.forbidden(
          "Role '" + identity.role() + "' does not belong in a " + implied.get() + " context");
    }
    return declared.get();
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
