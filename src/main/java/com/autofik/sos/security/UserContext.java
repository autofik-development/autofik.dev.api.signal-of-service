package com.autofik.sos.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/**
 * The {@code user-context} header the internal gateway attaches to every request it proxies here.
 *
 * <p>The gateway only writes it after its own token verification has already run, so the flags
 * below are a report of that verification — not a claim the caller gets to make. Unknown fields are
 * ignored on purpose: the gateway may add to this payload without this service needing a release.
 *
 * <p>The block also carries the driver's SSO profile (phone, username, vehicles) so this service
 * can keep its own copy in step, see {@code UserProfileSync}.
 *
 * <p>Calls from the mechanic app arrive under {@code mechanic-context} instead — see {@link
 * MechanicContext}.
 *
 * @see MicroserviceTokenSdk for what is trusted and what is rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserContext(User user) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record User(
      String uuid,
      String role,
      String deviceId,
      @JsonProperty("otpVerify") Boolean otpVerify,
      @JsonProperty("profileVerify") Boolean profileVerify,
      String tokenStatus,
      @JsonProperty("isLogout") Boolean isLogout,
      String phoneNumber,
      Profile profile,
      List<ContextVehicle> vehicles)
      implements ContextIdentity {

    /**
     * Empty: this header serves drivers and mechanics both, so the role string is all there is to
     * go on and a role this service has no mapping for is refused rather than defaulted.
     */
    @Override
    public Optional<CallerRole> impliedRole() {
      return Optional.empty();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Profile(String username, String profileImage) {}

  /** One of the driver's vehicles as SSO describes it; {@code id} is SSO's id, not a UUID. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ContextVehicle(
      String id,
      String carBrand,
      String carModel,
      String carModelYear,
      String fuelType,
      String licensePlate,
      String image) {}
}
