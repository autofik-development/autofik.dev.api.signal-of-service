package com.autofik.sos.dto.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Billing's answer to a verify call. Unknown fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BillingVerifyResponse(String status, String message, String code, Data data) {

  /**
   * @param tokenBalance null for a plan without tokens (to be confirmed by billing); read live and
   *     never stored
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      String userId,
      String billingId,
      String planId,
      String tier,
      Integer tokenBalance,
      Boolean expired,
      Boolean canUse) {}
}
