package com.autofik.sos.dto.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Billing's answer to a consume call. Unknown fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BillingConsumeResponse(String status, String message, String code, Data data) {

  /**
   * @param alreadyProcessed true when this requestId was cut before; nothing was cut again
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      String userId,
      String billingId,
      String requestId,
      Integer consumed,
      Integer tokenBalance,
      Boolean alreadyProcessed) {}
}
