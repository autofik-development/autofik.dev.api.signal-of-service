package com.autofik.sos.security;

import java.util.function.UnaryOperator;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the request context the internal gateway attaches to every request it proxies here — most
 * importantly {@code user-context} and {@code mechanic-context}, which carry the identity the
 * gateway already verified.
 *
 * <p>This is the only place userId/mechanicId/role are ever read from; nothing here ever looks at
 * the request body. Headers are fetched through a lookup function rather than a servlet request so
 * the WebSocket handshake, which has no {@code HttpServletRequest}, reads the same contract.
 *
 * @param serviceCode which internal service proxied this call
 * @param userContext the verified driver identity, or null when the header was absent or
 *     unparseable
 * @param mechanicContext the verified mechanic identity, on the same terms
 * @param userAgent the calling app, for logs only
 * @param ipAddress the client address as the edge saw it, for logs only
 * @param authorization the caller's bearer, kept for reads that SSO scopes to its owner
 */
public record AppHeaderContext(
    String serviceCode,
    UserContext userContext,
    MechanicContext mechanicContext,
    String userAgent,
    String ipAddress,
    String authorization) {

  public static final String SERVICE_CODE_HEADER = "service-code";
  public static final String USER_CONTEXT_HEADER = "user-context";
  public static final String MECHANIC_CONTEXT_HEADER = "mechanic-context";
  public static final String USER_AGENT_HEADER = "user-agent";
  public static final String FORWARDED_FOR_HEADER = "x-forwarded-for";
  public static final String AUTHORIZATION_HEADER = "authorization";

  public static AppHeaderContext from(UnaryOperator<String> headers, ObjectMapper objectMapper) {
    String rawUser = headers.apply(USER_CONTEXT_HEADER);
    String rawMechanic = headers.apply(MECHANIC_CONTEXT_HEADER);
    return new AppHeaderContext(
        headers.apply(SERVICE_CODE_HEADER),
        // Some deployments carry the mechanic block inside user-context rather than sending a
        // header of its own, and some send only mechanic-context. Each record ignores the keys it
        // does not own, so reading whichever header arrived as either shape costs one parse and
        // saves this service caring which arrangement the edge is on.
        parse(rawUser != null ? rawUser : rawMechanic, UserContext.class, objectMapper),
        parse(rawMechanic != null ? rawMechanic : rawUser, MechanicContext.class, objectMapper),
        headers.apply(USER_AGENT_HEADER),
        firstForwardedFor(headers.apply(FORWARDED_FOR_HEADER)),
        headers.apply(AUTHORIZATION_HEADER));
  }

  /** The user block, or null when the header was missing, malformed, or carried no user. */
  public UserContext.User user() {
    return userContext == null ? null : userContext.user();
  }

  /** The mechanic block, on the same terms as {@link #user()}. */
  public MechanicContext.Mechanic mechanic() {
    return mechanicContext == null ? null : mechanicContext.mechanic();
  }

  /**
   * Whichever block names the caller, or null when neither does.
   *
   * <p>The mechanic block wins when both arrived, because it is the more specific claim and the
   * driver block cannot say anything the mechanic one does not.
   *
   * @throws UserContextRejectedException when the two blocks name different people — the edge is
   *     misconfigured, and neither identity is more trustworthy than the other, so acting on either
   *     risks acting as the wrong one.
   */
  public ContextIdentity identity() {
    UserContext.User user = user();
    MechanicContext.Mechanic mechanic = mechanic();

    if (mechanic == null) {
      return user;
    }
    if (user == null) {
      return mechanic;
    }

    String userId = subject(user);
    String mechanicId = subject(mechanic);
    if (userId != null && mechanicId != null && !userId.equalsIgnoreCase(mechanicId)) {
      throw UserContextRejectedException.unauthorized("Conflicting identity context");
    }
    // A block that names nobody is not a claim; the one that does stands.
    return mechanicId != null ? mechanic : user;
  }

  /** The uuid a block names, or null when it names nobody. */
  private static String subject(ContextIdentity identity) {
    if (identity == null || identity.uuid() == null || identity.uuid().isBlank()) {
      return null;
    }
    return identity.uuid().trim();
  }

  private static <T> T parse(String raw, Class<T> type, ObjectMapper objectMapper) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, type);
    } catch (Exception malformed) {
      // A header we cannot parse is a header we do not trust; the caller stays anonymous.
      // Never log the raw value — it names the user and their device.
      return null;
    }
  }

  /**
   * X-Forwarded-For accumulates a comma-separated chain as it crosses proxies; the client is the
   * first entry. Only the leftmost is of any use in a log line.
   */
  private static String firstForwardedFor(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    int comma = raw.indexOf(',');
    return (comma < 0 ? raw : raw.substring(0, comma)).trim();
  }
}
