package com.autofik.sos.service;

import com.autofik.sos.dto.external.BillingVerifyResponse;
import java.util.UUID;

/** Calls the internal billing service. */
public interface BillingClient {

  /**
   * Billing's view of one account's plan.
   *
   * @param role the account's SSO role as SOS stored it, or null when unknown
   * @throws com.autofik.sos.exception.PlanRequiredException when billing has no plan for the user
   * @throws com.autofik.sos.exception.ServiceUnavailableException when billing cannot answer
   */
  BillingVerifyResponse.Data verify(UUID userId, String role);
}
