package com.autofik.sos.security;

import org.springframework.http.HttpStatus;

/**
 * A {@code user-context} that arrived but cannot be acted on.
 *
 * <p>Distinct from an absent context, which simply leaves the caller anonymous and lets the filter
 * chain answer. This one is thrown only when the gateway named someone whose session is over (401)
 * or whose account is not cleared for this service (403) — the app needs to tell those two apart,
 * since one means log in again and the other means go finish verification.
 */
public class UserContextRejectedException extends RuntimeException {

  private final HttpStatus status;

  public UserContextRejectedException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }

  public static UserContextRejectedException unauthorized(String message) {
    return new UserContextRejectedException(HttpStatus.UNAUTHORIZED, message);
  }

  public static UserContextRejectedException forbidden(String message) {
    return new UserContextRejectedException(HttpStatus.FORBIDDEN, message);
  }
}
