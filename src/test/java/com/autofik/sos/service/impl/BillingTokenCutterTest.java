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
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.dto.external.BillingConsumeResponse;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.repository.MechanicRepository;
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
  private final MechanicRepository mechanics = mock(MechanicRepository.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final BillingConfig config = new BillingConfig();
  private final UUID sosId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID mechanicId = UUID.randomUUID();
  private BillingTokenCutter cutter;

  @BeforeEach
  void setUp() {
    config.setEnabled(true);
    cutter = new BillingTokenCutter(config, client, users, mechanics, meters);
    when(users.findById(userId))
        .thenReturn(Optional.of(User.builder().userId(userId).role("CUSTOMER").build()));
    when(mechanics.findById(mechanicId))
        .thenReturn(Optional.of(Mechanic.builder().mechanicId(mechanicId).role("GARAGE").build()));
  }

  private static BillingConsumeResponse.Data cut(boolean replayed) {
    return new BillingConsumeResponse.Data("u", "b", "r", 1, 399, replayed);
  }

  @Test
  void customer_isCutWithItsOwnRequestId_andTheStoredRole() {
    when(client.consume(any(), any(), any())).thenReturn(cut(false));

    cutter.consumeCustomer(sosId, userId, 0);

    verify(client).consume(userId, sosId + ":customer", "CUSTOMER");
  }

  @Test
  void mechanic_isCutWithItsOwnRequestId_andTheStoredRole() {
    when(client.consume(any(), any(), any())).thenReturn(cut(false));

    cutter.consumeMechanic(sosId, mechanicId, 0);

    verify(client).consume(mechanicId, sosId + ":mechanic", "GARAGE");
  }

  @Test
  void aTimeout_retriesWithTheSameRequestId_thenSucceeds() {
    when(client.consume(any(), any(), any()))
        .thenThrow(new ServiceUnavailableException("down"))
        .thenReturn(cut(true));

    cutter.consumeCustomer(sosId, userId, 0);

    verify(client, times(2)).consume(userId, sosId + ":customer", "CUSTOMER");
    assertThat(meters.find("sos.billing.consume.failed").counter()).isNull();
  }

  @Test
  void billingDownForGood_givesUpAfterTheAttempts_andCountsIt() {
    when(client.consume(any(), any(), any())).thenThrow(new ServiceUnavailableException("down"));

    cutter.consumeMechanic(sosId, mechanicId, 0);

    verify(client, times(BillingTokenCutter.ATTEMPTS)).consume(any(), any(), any());
    assertThat(
            meters.get("sos.billing.consume.failed").tag("reason", "UNAVAILABLE").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void aDefiniteRefusal_isNotRetried() {
    when(client.consume(any(), any(), any()))
        .thenThrow(new PlanRequiredException(PlanRequiredException.Reason.NO_TOKENS));

    cutter.consumeCustomer(sosId, userId, 0);

    verify(client, times(1)).consume(any(), any(), any());
    assertThat(
            meters.get("sos.billing.consume.failed").tag("reason", "NO_TOKENS").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void oneSideFailing_neverStopsTheOther() {
    when(client.consume(eq(userId), any(), any()))
        .thenThrow(new PlanRequiredException(PlanRequiredException.Reason.NO_TOKENS));
    when(client.consume(eq(mechanicId), any(), any())).thenReturn(cut(false));

    cutter.consumeCustomer(sosId, userId, 0);
    cutter.consumeMechanic(sosId, mechanicId, 0);

    verify(client).consume(mechanicId, sosId + ":mechanic", "GARAGE");
  }

  @Test
  void aRowWithoutAStoredRole_isSentAsItsFamilyDefault() {
    UUID otherUser = UUID.randomUUID();
    UUID otherMechanic = UUID.randomUUID();
    when(users.findById(otherUser)).thenReturn(Optional.empty());
    when(mechanics.findById(otherMechanic)).thenReturn(Optional.empty());
    when(client.consume(any(), any(), any())).thenReturn(cut(false));

    cutter.consumeCustomer(sosId, otherUser, 0);
    cutter.consumeMechanic(sosId, otherMechanic, 0);

    verify(client).consume(eq(otherUser), any(), eq("user"));
    verify(client).consume(eq(otherMechanic), any(), eq("mechanic"));
  }

  @Test
  void billingOff_callsNothing() {
    config.setEnabled(false);

    cutter.consumeAsync(sosId, userId, mechanicId);

    verify(client, never()).consume(any(), any(), any());
  }

  @Test
  void billingOn_consumeAsync_cutsBothOnTheirOwnThreads() {
    when(client.consume(any(), any(), any())).thenReturn(cut(false));

    cutter.consumeAsync(sosId, userId, mechanicId);

    verify(client, timeout(5000)).consume(userId, sosId + ":customer", "CUSTOMER");
    verify(client, timeout(5000)).consume(mechanicId, sosId + ":mechanic", "GARAGE");
  }

  @Test
  void anInterruptedWait_givesUpAtOnce_andKeepsTheInterrupt() {
    when(client.consume(any(), any(), any())).thenThrow(new ServiceUnavailableException("down"));
    Thread.currentThread().interrupt();
    try {
      cutter.consumeCustomer(sosId, userId, 1000);

      verify(client, times(1)).consume(any(), any(), any());
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear the flag so it does not leak into the next test
    }
  }
}
