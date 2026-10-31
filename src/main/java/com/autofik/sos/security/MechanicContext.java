package com.autofik.sos.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/**
 * The {@code mechanic-context} header the internal gateway attaches to calls from the mechanic app.
 *
 * <p>The same report as {@link UserContext}, under a {@code mechanic} key: the gateway has already
 * verified the bearer, and this is what it found. Some deployments send the mechanic block inside
 * {@code user-context} instead of a header of its own — both are read, see {@link
 * AppHeaderContext}.
 *
 * <p>The block also carries the mechanic's SSO profile (name, phone, skill tags, service radius,
 * rating) so this service can keep its own copy in step, see {@code MechanicProfileSync}. SSO's own
 * profile response wraps the block in {@code data}; {@link #mechanic()} accepts either shape.
 *
 * <p>Unknown fields are ignored on purpose: the gateway may add to this payload without this
 * service needing a release.
 *
 * @see MicroserviceTokenSdk for what is trusted and what is rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MechanicContext(Mechanic mechanic, Data data) {

  /** SSO's response envelope, forwarded as is by some gateways. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(Mechanic mechanic) {}

  /** The mechanic block, from the top level or from SSO's {@code data} envelope. */
  @Override
  public Mechanic mechanic() {
    if (mechanic != null) {
      return mechanic;
    }
    return data == null ? null : data.mechanic();
  }

  /**
   * @param phoneNumber SSO's phone for the mechanic
   * @param mechanicType {@code freelance} or the garage value
   * @param profile name and email
   * @param skillService skill tags and service radius
   * @param avgRate SSO's rating as text, for example {@code "4.80"}; kept as text so a bad value
   *     cannot make the whole header unparseable
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Mechanic(
      String uuid,
      String role,
      String deviceId,
      @JsonProperty("otpVerify") Boolean otpVerify,
      @JsonProperty("profileVerify") Boolean profileVerify,
      String tokenStatus,
      @JsonProperty("isLogout") Boolean isLogout,
      String phoneNumber,
      String mechanicType,
      Profile profile,
      SkillService skillService,
      String avgRate)
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

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Profile(String fullName, String email, String profileImage) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkillService(List<String> skillTag, Integer serviceRadiusKm) {}
}
