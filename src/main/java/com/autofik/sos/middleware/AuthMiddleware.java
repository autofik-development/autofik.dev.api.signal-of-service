package com.autofik.sos.middleware;

import com.autofik.sos.security.AuthErrorResponder;
import com.autofik.sos.security.AuthMechanic;
import com.autofik.sos.security.AuthUser;
import com.autofik.sos.security.Caller;
import com.autofik.sos.security.CurrentUser;
import com.autofik.sos.security.GatewayIdentityFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requires a resolved identity on the routes it is registered for, and publishes it where handlers
 * can read it.
 *
 * <p>The identity itself is resolved once, earlier, by {@link GatewayIdentityFilter} — the security
 * chain needs it in place before it can authorise anything, and re-reading the headers here would
 * mean two places deciding who the caller is. This reads what the filter proved and puts it on the
 * request, which is what makes {@link AuthUser} and {@link AuthMechanic} work.
 *
 * <p>Registered per route rather than globally (see {@code WebConfig#addInterceptors}), so that
 * which endpoints demand a caller is one list someone can read, instead of a property of whichever
 * matcher in the security config happens to win.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthMiddleware implements HandlerInterceptor {

  /** Where the resolved caller lives for the rest of the request, whichever role they hold. */
  public static final String CURRENT_USER_ATTRIBUTE = "currentUser";

  /**
   * The same caller again, published only when the gateway's context said mechanic.
   *
   * <p>Two attributes rather than one plus a role check in every handler: a mechanic-only route
   * that forgets the check reads as working right up until a driver calls it, and by then it is
   * bidding on its own breakdown. An absent attribute is what {@link AuthMechanic} turns into a
   * 403, so the check cannot be forgotten — it is the parameter.
   */
  public static final String CURRENT_MECHANIC_ATTRIBUTE = "currentMechanic";

  private final AuthErrorResponder errorResponder;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    // A browser cannot put identity headers on a preflight, and there is nothing to protect in
    // the answer to one. Rejecting it would only break CORS for the dashboard.
    if (CorsUtils.isPreFlightRequest(request)) {
      return true;
    }

    Optional<CurrentUser> caller = Caller.current();
    if (caller.isEmpty()) {
      // Normally unreachable: the security chain answers an anonymous request before a handler is
      // ever looked up. It stays because the day someone opens a matcher in SecurityConfig, this
      // is what keeps a handler from running with no caller at all.
      log.warn(
          "No caller context on {} {} — the gateway did not send one, or SecurityConfig permits"
              + " this route",
          request.getMethod(),
          request.getRequestURI());
      errorResponder.write(response, HttpStatus.UNAUTHORIZED, "Missing or invalid user context");
      return false;
    }

    CurrentUser current = caller.get();
    request.setAttribute(CURRENT_USER_ATTRIBUTE, current);
    if (current.isMechanic()) {
      request.setAttribute(CURRENT_MECHANIC_ATTRIBUTE, current);
    }
    return true;
  }
}
