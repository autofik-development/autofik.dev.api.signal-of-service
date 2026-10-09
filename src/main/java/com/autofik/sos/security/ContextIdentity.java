package com.autofik.sos.security;

import java.util.Optional;

/**
 * The block of a gateway context header that names the caller.
 *
 * <p>{@code user-context} and {@code mechanic-context} carry the same verification report under
 * different keys, and the check that decides whether this service will act on one — role recognised
 * (session and otp/profile verification are the internal gateway's job) — is the same either way.
 * They live here once so the two headers cannot drift into trusting different things.
 *
 * @see MicroserviceTokenSdk for where these are applied.
 */
public sealed interface ContextIdentity permits UserContext.User, MechanicContext.Mechanic {

  String uuid();

  String role();

  String deviceId();

  /**
   * The role the block itself implies, regardless of the role string inside it.
   *
   * <p>Empty for {@code user-context}, which serves drivers and mechanics alike and so has only its
   * role field to go on. Present for {@code mechanic-context}, where the key is already the claim —
   * which also makes a role string that disagrees with it a misconfiguration worth refusing rather
   * than picking a winner from.
   */
  Optional<CallerRole> impliedRole();
}
