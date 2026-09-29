package com.autofik.sos.security;

import java.util.UUID;

/**
 * The identity {@link MicroserviceTokenSdk} resolved for the current request.
 *
 * <p>Held in the {@code details} slot of the request's Authentication, so anything past the filter
 * can read the caller's role, device and calling service without going back to the headers. The
 * principal itself stays a bare {@link UUID} — every controller already takes it that way.
 *
 * @param id the caller's SSO uuid, which is also the id of their local User/Mechanic projection
 * @param role how this service treats them, not the raw SSO role string
 * @param deviceId the device SSO issued the session to; null when the gateway did not send one
 * @param serviceCode which internal service proxied the call; null outside the gateway
 */
public record CurrentUser(UUID id, CallerRole role, String deviceId, String serviceCode) {

  public String authority() {
    return role.authority();
  }

  public boolean isMechanic() {
    return role == CallerRole.MECHANIC;
  }
}
