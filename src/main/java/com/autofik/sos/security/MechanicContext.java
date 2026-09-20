package com.autofik.sos.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

/**
 * The {@code mechanic-context} header the internal gateway attaches to calls from the mechanic app.
 *
 * <p>The same report as {@link UserContext}, under a {@code mechanic} key: the gateway has already
 * verified the bearer, and this is what it found. Some deployments send the mechanic block inside
 * {@code user-context} instead of a header of its own — both are read, see {@link
 * AppHeaderContext}.
 *
 * <p>Unknown fields are ignored on purpose: the gateway may add to this payload without this
 * service needing a release.
 *
 * @see MicroserviceTokenSdk for what is trusted and what is rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MechanicContext(Mechanic mechanic) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Mechanic(
      String uuid,
      String role,
      String deviceId,
      @JsonProperty("otpVerify") Boolean otpVerify,
      @JsonProperty("profileVerify") Boolean profileVerify,
      String tokenStatus,
      @JsonProperty("isLogout") Boolean isLogout)
      implements ContextIdentity {

    /**
     * A mechanic block names a mechanic even when the gateway left the role string out, so this
     * service does not have to turn away a mechanic mid-job over a field the key already answered.
     */
    @Override
    public Optional<CallerRole> impliedRole() {
      return Optional.of(CallerRole.MECHANIC);
    }
  }
}
