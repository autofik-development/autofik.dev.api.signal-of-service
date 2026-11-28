package com.autofik.sos.service.impl;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.BillingClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Cuts the customer's one token once an SOS job is done. The job is already finished, so nothing
 * here may fail it: the call runs on its own thread, retries with the same {@code requestId}
 * (billing never cuts that id twice), and gives up with an error log and a counter.
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

  private final BillingConfig config;
  private final BillingClient client;
  private final UserRepository userRepository;
  private final MeterRegistry meters;

  /** Fire and forget; call after the completing transaction has committed. */
  public void consumeAsync(UUID sosId, UUID userId) {
    if (!config.isEnabled()) {
      return;
    }
    Thread.startVirtualThread(() -> consume(sosId, userId, 1000L));
  }

  void consume(UUID sosId, UUID userId, long backoffMs) {
    String requestId = sosId.toString();
    String role = userRepository.findById(userId).map(User::getRole).orElse(DEFAULT_CUSTOMER_ROLE);
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        var cut = client.consume(userId, requestId, role);
        meters
            .counter("sos.billing.consume", "replayed", String.valueOf(cut.alreadyProcessed()))
            .increment();
        return;
      } catch (PlanRequiredException e) {
        // Billing answered for good (no plan, expired, no tokens); asking again changes nothing.
        log.warn("Token not cut for sos {}: {}", requestId, e.reason());
        meters.counter("sos.billing.consume.failed", "reason", e.reason().name()).increment();
        return;
      } catch (RuntimeException e) {
        log.warn("Token cut for sos {} failed, attempt {}/{}", requestId, attempt, ATTEMPTS);
        if (attempt < ATTEMPTS && !sleep(backoffMs * attempt)) {
          break;
        }
      }
    }
    log.error("Token never cut for sos {} after {} attempts", requestId, ATTEMPTS);
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
