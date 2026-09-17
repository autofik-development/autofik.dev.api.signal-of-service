package com.autofik.sos.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@code user-context} header the internal gateway attaches to every request it proxies here.
 *
 * <p>The gateway only writes it after its own token verification has already run, so the flags
 * below are a report of that verification — not a claim the caller gets to make. Unknown fields are
 * ignored on purpose: the gateway may add to this payload without this service needing a release.
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
      @JsonProperty("isLogout") Boolean isLogout) {

    public boolean otpVerified() {
      return Boolean.TRUE.equals(otpVerify);
    }

    public boolean profileVerified() {
      return Boolean.TRUE.equals(profileVerify);
    }

    public boolean loggedOut() {
      return Boolean.TRUE.equals(isLogout);
    }
  }
}
