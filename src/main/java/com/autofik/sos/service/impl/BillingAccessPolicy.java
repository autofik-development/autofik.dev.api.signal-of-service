package com.autofik.sos.service.impl;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.dto.external.BillingVerifyResponse;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.exception.PlanRequiredException.Reason;
import com.autofik.sos.security.CallerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The business rule: may this plan use SOS? Checks run in the order the app explains them. */
@Component
@RequiredArgsConstructor
public class BillingAccessPolicy {

  private final BillingConfig config;

  /**
   * @throws PlanRequiredException with the first check that fails
   */
  public void check(CallerRole role, BillingVerifyResponse.Data plan) {
    if (Boolean.TRUE.equals(plan.expired())) {
      throw new PlanRequiredException(Reason.EXPIRED);
    }
    List<String> allowed =
        role == CallerRole.MECHANIC
            ? config.getAllowedTiers().getMechanic()
            : config.getAllowedTiers().getCustomer();
    if (!allowed.isEmpty() && !isAllowed(allowed, plan)) {
      throw new PlanRequiredException(Reason.TIER_NOT_ALLOWED);
    }
    // null means a plan without tokens: nothing to count. ponytail: billing to confirm that shape.
    if (plan.tokenBalance() != null && plan.tokenBalance() <= 0) {
      throw new PlanRequiredException(Reason.NO_TOKENS);
    }
    // Billing's own verdict, after ours, so a refusal still names the specific reason above.
    if (Boolean.FALSE.equals(plan.canUse())) {
      throw new PlanRequiredException(Reason.NOT_ENTITLED);
    }
  }

  private static boolean isAllowed(List<String> allowed, BillingVerifyResponse.Data plan) {
    return allowed.stream()
        .map(String::trim)
        .anyMatch(a -> a.equalsIgnoreCase(plan.tier()) || a.equalsIgnoreCase(plan.planId()));
  }
}
