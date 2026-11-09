package com.autofik.sos.service.impl;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.dto.external.BillingVerifyResponse;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.security.CallerRole;
import com.autofik.sos.service.BillingClient;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asks billing live whether a customer (at SOS create) or a mechanic (at bid) may go on, saves
 * billing's plan on that person's row, then applies {@link BillingAccessPolicy}. Call it outside
 * any transaction. Does nothing while {@code billing.enabled} is false.
 *
 * <p>The saved plan is a snapshot for places that cannot call billing. It never decides: this live
 * answer does. The token balance is read here and never stored.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BillingGate {

  private final BillingConfig config;
  private final BillingClient client;
  private final BillingAccessPolicy policy;
  private final UserRepository userRepository;
  private final MechanicRepository mechanicRepository;
  private final TransactionTemplate tx;

  public void requireCustomer(UUID userId) {
    if (!config.isEnabled()) {
      return;
    }
    String role = userRepository.findById(userId).map(User::getRole).orElse(null);
    BillingVerifyResponse.Data plan = verified(userId, role);
    saveQuietly(
        () ->
            userRepository.updatePlan(
                userId,
                plan.billingId(),
                plan.planId(),
                plan.tier(),
                plan.expired(),
                plan.canUse(),
                LocalDateTime.now()));
    policy.check(CallerRole.USER, plan);
  }

  public void requireMechanic(UUID mechanicId) {
    if (!config.isEnabled()) {
      return;
    }
    String role = mechanicRepository.findById(mechanicId).map(Mechanic::getRole).orElse(null);
    BillingVerifyResponse.Data plan = verified(mechanicId, role);
    saveQuietly(
        () ->
            mechanicRepository.updatePlan(
                mechanicId,
                plan.billingId(),
                plan.planId(),
                plan.tier(),
                plan.expired(),
                plan.canUse(),
                LocalDateTime.now()));
    policy.check(CallerRole.MECHANIC, plan);
  }

  private BillingVerifyResponse.Data verified(UUID id, String role) {
    BillingVerifyResponse.Data plan = client.verify(id, role);
    // Billing must answer for the account that asked; never write one account's plan to another.
    if (plan.userId() == null || !plan.userId().equalsIgnoreCase(id.toString())) {
      log.error("Billing answered for a different account than {}", id);
      throw new ServiceUnavailableException("Unable to verify the billing plan");
    }
    return plan;
  }

  // A failed save must not fail the create or bid: the copy is only a hint.
  private void saveQuietly(Runnable save) {
    try {
      tx.executeWithoutResult(status -> save.run());
    } catch (RuntimeException e) {
      log.warn("Could not save the billing plan: {}", e.toString());
    }
  }
}
