package com.autofik.sos.security;

import java.util.Locale;
import java.util.Optional;

/**
 * The two kinds of caller this service serves, and the SSO role names that map onto each.
 *
 * <p>SSO names more roles than SOS has meaning for: a GARAGE account acts on a breakdown exactly as
 * a MECHANIC does, and CUSTOM/CUSTOMER are the same driver as USER. Anything outside this list is
 * rejected rather than defaulted — a role we do not recognise is not a role we can authorise.
 */
public enum CallerRole {
  USER("ROLE_USER"),
  MECHANIC("ROLE_MECHANIC");

  private final String authority;

  CallerRole(String authority) {
    this.authority = authority;
  }

  /** The Spring Security authority, which is what {@code hasRole('USER')} checks against. */
  public String authority() {
    return authority;
  }

  /** Empty when SSO sent a role this service has no mapping for. */
  public static Optional<CallerRole> fromSsoRole(String role) {
    if (role == null) {
      return Optional.empty();
    }
    return switch (role.trim().toUpperCase(Locale.ROOT)) {
      case "MECHANIC", "GARAGE" -> Optional.of(MECHANIC);
      case "USER", "CUSTOM", "CUSTOMER" -> Optional.of(USER);
      default -> Optional.empty();
    };
  }
}
