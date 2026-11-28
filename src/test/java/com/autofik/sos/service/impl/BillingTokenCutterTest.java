package com.autofik.sos.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.dto.external.BillingConsumeResponse;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.BillingClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BillingTokenCutterTest {

  private final BillingClient client = mock(BillingClient.class);
  private final UserRepository users = mock(UserRepository.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final BillingConfig config = new BillingConfig();
  private final UUID sosId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private BillingTokenCutter cutter;

  @BeforeEach
  void setUp() {
    config.setEnabled(true);
    cutter = new BillingTokenCutter(config, client, users, meters);
    when(users.findById(userId))
        .thenReturn(Optional.of(User.builder().userId(userId).role("CUSTOMER").build()));
  }

  private static BillingConsumeResponse.Data cut(boolean replayed) {
    return new BillingConsumeResponse.Data("u", "b", "r", 1, 399, replayed);
  }

  @Test
  void cutsOneToken_withTheSosIdAsRequestId_andTheStoredRole() {
    when(client.consume(any(), any(), any())).thenReturn(cut(false));

    cutter.consume(sosId, userId, 0);

    verify(client).consume(userId, sosId.toString(), "CUSTOMER");
  }

  @Test
  void aTimeout_retriesWithTheSameRequestId_thenSucceeds() {
    when(client.consume(any(), any(), any()))
        .thenThrow(new ServiceUnavailableException("down"))
        .thenReturn(cut(true));

    cutter.consume(sosId, userId, 0);

    verify(client, times(2)).consume(userId, sosId.toString(), "CUSTOMER");
    assertThat(meters.find("sos.billing.consume.failed").counter()).isNull();
  }

  @Test
  void billingDownForGood_givesUpAfterTheAttempts_andCountsIt() {
    when(client.consume(any(), any(), any())).thenThrow(new ServiceUnavailableException("down"));

    cutter.consume(sosId, userId, 0);

    verify(client, times(BillingTokenCutter.ATTEMPTS)).consume(any(), any(), any());
    assertThat(
            meters.get("sos.billing.consume.failed").tag("reason", "UNAVAILABLE").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void aDefiniteRefusal_isNotRetried() {
    when(client.consume(any(), any(), any()))
        .thenThrow(new PlanRequiredException(PlanRequiredException.Reason.NO_TOKENS));

    cutter.consume(sosId, userId, 0);

    verify(client, times(1)).consume(any(), any(), any());
    assertThat(
            meters.get("sos.billing.consume.failed").tag("reason", "NO_TOKENS").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void aCustomerWithoutAStoredRole_isSentAsUser() {
    UUID other = UUID.randomUUID();
    when(users.findById(other)).thenReturn(Optional.empty());
    when(client.consume(any(), any(), any())).thenReturn(cut(false));

    cutter.consume(sosId, other, 0);

    verify(client).consume(eq(other), any(), eq("user"));
  }

  @Test
  void billingOff_callsNothing() {
    config.setEnabled(false);

    cutter.consumeAsync(sosId, userId);

    verify(client, never()).consume(any(), any(), any());
  }

  @Test
  void billingOn_consumeAsync_cutsOnItsOwnThread() {
    when(client.consume(any(), any(), any())).thenReturn(cut(false));

    cutter.consumeAsync(sosId, userId);

    verify(client, timeout(5000)).consume(userId, sosId.toString(), "CUSTOMER");
  }

  @Test
  void anInterruptedWait_givesUpAtOnce_andKeepsTheInterrupt() {
    when(client.consume(any(), any(), any())).thenThrow(new ServiceUnavailableException("down"));
    Thread.currentThread().interrupt();
    try {
      cutter.consume(sosId, userId, 1000);

      verify(client, times(1)).consume(any(), any(), any());
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear the flag so it does not leak into the next test
    }
  }
}
