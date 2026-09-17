package com.autofik.sos.security;

import java.util.function.UnaryOperator;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the request context the internal gateway attaches to every request it proxies here — most
 * importantly {@code user-context}, which carries the identity the gateway already verified.
 *
 * <p>This is the only place userId/role are ever read from; nothing here ever looks at the request
 * body. Headers are fetched through a lookup function rather than a servlet request so the
 * WebSocket handshake, which has no {@code HttpServletRequest}, reads the same contract.
 *
 * @param serviceCode which internal service proxied this call
 * @param userContext the verified identity, or null when the header was absent or unparseable
 * @param userAgent the calling app, for logs only
 * @param ipAddress the client address as the edge saw it, for logs only
 * @param authorization the caller's bearer, kept for reads that SSO scopes to its owner
 */
public record AppHeaderContext(
    String serviceCode,
    UserContext userContext,
    String userAgent,
    String ipAddress,
    String authorization) {

  public static final String SERVICE_CODE_HEADER = "service-code";
  public static final String USER_CONTEXT_HEADER = "user-context";
  public static final String USER_AGENT_HEADER = "user-agent";
  public static final String FORWARDED_FOR_HEADER = "x-forwarded-for";
  public static final String AUTHORIZATION_HEADER = "authorization";

  public static AppHeaderContext from(UnaryOperator<String> headers, ObjectMapper objectMapper) {
    return new AppHeaderContext(
        headers.apply(SERVICE_CODE_HEADER),
        parseUserContext(headers.apply(USER_CONTEXT_HEADER), objectMapper),
        headers.apply(USER_AGENT_HEADER),
        firstForwardedFor(headers.apply(FORWARDED_FOR_HEADER)),
        headers.apply(AUTHORIZATION_HEADER));
  }

  /** The user block, or null when the header was missing, malformed, or carried no user. */
  public UserContext.User user() {
    return userContext == null ? null : userContext.user();
  }

  private static UserContext parseUserContext(String raw, ObjectMapper objectMapper) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, UserContext.class);
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
