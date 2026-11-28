package com.autofik.sos.service.impl;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.BillingClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Cuts one token from the customer and one from the mechanic once an SOS job is done. The job is
 * already finished, so nothing here may fail it: each cut runs on its own thread, retries with the
 * same {@code requestId} (billing never cuts that id twice), and gives up with an error log and a
 * counter. The two cuts are independent: one failing never stops the other.
 *
 * <p>The request ids are {@code <sosId>:customer} and {@code <sosId>:mechanic}, so the two cuts of
 * one job never share an id.
 *
 * <p>ponytail: no dead-letter replay. A cut that still fails after the retries is only logged and
 * counted ({@code sos.billing.consume.failed}); add a sos.dlq event if finance wants a replay.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BillingTokenCutter {

  static final int ATTEMPTS = 3;
  private static final String DEFAULT_CUSTOMER_ROLE = "user";
  private static final String DEFAULT_MECHANIC_ROLE = "mechanic";

  private final BillingConfig config;
  private final BillingClient client;
  private final UserRepository userRepository;
  private final MechanicRepository mechanicRepository;
  private final MeterRegistry meters;

  /** Fire and forget; call after the completing transaction has committed. */
  public void consumeAsync(UUID sosId, UUID userId, UUID mechanicId) {
    if (!config.isEnabled()) {
      return;
    }
    Thread.startVirtualThread(() -> consumeCustomer(sosId, userId, 1000L));
    Thread.startVirtualThread(() -> consumeMechanic(sosId, mechanicId, 1000L));
  }

  void consumeCustomer(UUID sosId, UUID userId, long backoffMs) {
    String role = userRepository.findById(userId).map(User::getRole).orElse(DEFAULT_CUSTOMER_ROLE);
    cut(sosId + ":customer", userId, role, backoffMs);
  }

  void consumeMechanic(UUID sosId, UUID mechanicId, long backoffMs) {
    String role =
        mechanicRepository
            .findById(mechanicId)
            .map(Mechanic::getRole)
            .orElse(DEFAULT_MECHANIC_ROLE);
    cut(sosId + ":mechanic", mechanicId, role, backoffMs);
  }

  private void cut(String requestId, UUID billedId, String role, long backoffMs) {
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        var cut = client.consume(billedId, requestId, role);
        meters
            .counter("sos.billing.consume", "replayed", String.valueOf(cut.alreadyProcessed()))
            .increment();
        return;
      } catch (PlanRequiredException e) {
        // Billing answered for good (no plan, expired, no tokens); asking again changes nothing.
        log.warn("Token not cut for {}: {}", requestId, e.reason());
        meters.counter("sos.billing.consume.failed", "reason", e.reason().name()).increment();
        return;
      } catch (RuntimeException e) {
        log.warn("Token cut for {} failed, attempt {}/{}", requestId, attempt, ATTEMPTS);
        if (attempt < ATTEMPTS && !sleep(backoffMs * attempt)) {
          break;
        }
      }
    }
    log.error("Token never cut for {} after {} attempts", requestId, ATTEMPTS);
    meters.counter("sos.billing.consume.failed", "reason", "UNAVAILABLE").increment();
  }

  private static boolean sleep(long ms) {
    try {
      Thread.sleep(ms);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
