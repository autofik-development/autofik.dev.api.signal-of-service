package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.dto.external.BillingVerifyResponse.Data;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.impl.BillingAccessPolicy;
import com.autofik.sos.service.impl.BillingGate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** The live verify, the saved plan and the access rule, in that order. */
class BillingGateTest {

  private final BillingConfig config = new BillingConfig();
  private final BillingClient client = mock(BillingClient.class);
  private final UserRepository users = mock(UserRepository.class);
  private final MechanicRepository mechanics = mock(MechanicRepository.class);
  private final TransactionTemplate tx =
      new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          return action.doInTransaction(new SimpleTransactionStatus());
        }
      };
  private final BillingGate gate =
      new BillingGate(config, client, new BillingAccessPolicy(config), users, mechanics, tx);
  private final UUID id = UUID.randomUUID();

  private Data plan(String owner, Integer tokens, Boolean expired) {
    return new Data(owner, "b1", "plan_pro", "Pro", tokens, expired, true);
  }

  @Test
  void disabled_callsNothing() {
    gate.requireCustomer(id);
    gate.requireMechanic(id);

    verifyNoInteractions(client, users, mechanics);
  }

  @Test
  void customer_sendsTheStoredRole_savesThePlan_andPasses() {
    config.setEnabled(true);
    when(users.findById(id))
        .thenReturn(Optional.of(User.builder().userId(id).role("CUSTOMER").build()));
    when(client.verify(id, "CUSTOMER")).thenReturn(plan(id.toString(), 3, false));

    assertThatCode(() -> gate.requireCustomer(id)).doesNotThrowAnyException();

    verify(users)
        .updatePlan(
            eq(id),
            eq("b1"),
            eq("plan_pro"),
            eq("Pro"),
            eq(false),
            eq(true),
            any(LocalDateTime.class));
  }

  @Test
  void mechanic_goesThroughTheMechanicTable() {
    config.setEnabled(true);
    when(mechanics.findById(id))
        .thenReturn(Optional.of(Mechanic.builder().mechanicId(id).role("GARAGE").build()));
    when(client.verify(id, "GARAGE")).thenReturn(plan(id.toString(), null, false));

    gate.requireMechanic(id);

    verify(mechanics)
        .updatePlan(
            eq(id),
            eq("b1"),
            eq("plan_pro"),
            eq("Pro"),
            eq(false),
            eq(true),
            any(LocalDateTime.class));
    verify(users, never()).updatePlan(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void anExpiredPlan_isStillSaved_thenRefused() {
    config.setEnabled(true);
    when(users.findById(id)).thenReturn(Optional.empty());
    when(client.verify(id, null)).thenReturn(plan(id.toString(), 3, true));

    assertThatThrownBy(() -> gate.requireCustomer(id)).isInstanceOf(PlanRequiredException.class);

    verify(users).updatePlan(eq(id), any(), any(), any(), eq(true), any(), any());
  }

  @Test
  void aNoPlanAnswer_savesNothing() {
    config.setEnabled(true);
    when(users.findById(id)).thenReturn(Optional.empty());
    when(client.verify(id, null))
        .thenThrow(new PlanRequiredException(PlanRequiredException.Reason.NO_PLAN));

    assertThatThrownBy(() -> gate.requireCustomer(id)).isInstanceOf(PlanRequiredException.class);

    verify(users, never()).updatePlan(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void billingDown_isUnavailable() {
    config.setEnabled(true);
    when(users.findById(id)).thenReturn(Optional.empty());
    when(client.verify(id, null)).thenThrow(new ServiceUnavailableException("down"));

    assertThatThrownBy(() -> gate.requireCustomer(id))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anAnswerForAnotherAccount_isRefused_andNeverSaved() {
    config.setEnabled(true);
    when(users.findById(id)).thenReturn(Optional.empty());
    when(client.verify(id, null)).thenReturn(plan(UUID.randomUUID().toString(), 3, false));

    assertThatThrownBy(() -> gate.requireCustomer(id))
        .isInstanceOf(ServiceUnavailableException.class);

    verify(users, never()).updatePlan(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void anAnswerWithoutAUserId_isRefused() {
    config.setEnabled(true);
    when(users.findById(id)).thenReturn(Optional.empty());
    when(client.verify(id, null)).thenReturn(plan(null, 3, false));

    assertThatThrownBy(() -> gate.requireCustomer(id))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void aFailedSave_doesNotFailTheRequest() {
    config.setEnabled(true);
    when(users.findById(id)).thenReturn(Optional.empty());
    when(client.verify(id, null)).thenReturn(plan(id.toString(), 3, false));
    when(users.updatePlan(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("db"));

    assertThatCode(() -> gate.requireCustomer(id)).doesNotThrowAnyException();
  }
}
