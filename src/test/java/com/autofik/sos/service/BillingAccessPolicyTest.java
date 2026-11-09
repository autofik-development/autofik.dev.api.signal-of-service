package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.dto.external.BillingVerifyResponse.Data;
import com.autofik.sos.exception.GlobalExceptionHandler;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.exception.PlanRequiredException.Reason;
import com.autofik.sos.security.CallerRole;
import com.autofik.sos.service.impl.BillingAccessPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The three access checks, in order, plus billing's own verdict. */
class BillingAccessPolicyTest {

  private final BillingConfig config = new BillingConfig();
  private final BillingAccessPolicy policy = new BillingAccessPolicy(config);

  private static Data plan(String tier, Integer tokens, Boolean expired, Boolean canUse) {
    return new Data("u", "b", "plan_" + tier, tier, tokens, expired, canUse);
  }

  private Reason refusal(CallerRole role, Data plan) {
    return org.assertj.core.api.Assertions.catchThrowableOfType(
            PlanRequiredException.class, () -> policy.check(role, plan))
        .reason();
  }

  @Test
  void aValidPlanWithTokens_passes() {
    assertThatCode(() -> policy.check(CallerRole.USER, plan("Pro", 3, false, true)))
        .doesNotThrowAnyException();
  }

  @Test
  void expired_isRefused_beforeAnythingElse() {
    config.getAllowedTiers().setCustomer(List.of("Gold"));
    assertThat(refusal(CallerRole.USER, plan("Pro", 0, true, false))).isEqualTo(Reason.EXPIRED);
  }

  @Test
  void aTierOutsideTheList_isRefused_perRole() {
    config.getAllowedTiers().setCustomer(List.of("Pro"));
    config.getAllowedTiers().setMechanic(List.of("Active"));

    assertThatCode(() -> policy.check(CallerRole.USER, plan("pro", 3, false, true)))
        .as("tier match ignores case")
        .doesNotThrowAnyException();
    assertThat(refusal(CallerRole.USER, plan("Free", 3, false, true)))
        .isEqualTo(Reason.TIER_NOT_ALLOWED);
    assertThat(refusal(CallerRole.MECHANIC, plan("Pro", 3, false, true)))
        .as("the customer list does not apply to a mechanic")
        .isEqualTo(Reason.TIER_NOT_ALLOWED);
  }

  @Test
  void thePlanIdCountsAsMuchAsTheTier() {
    config.getAllowedTiers().setCustomer(List.of("plan_Pro"));
    assertThatCode(() -> policy.check(CallerRole.USER, plan("Pro", 3, false, true)))
        .doesNotThrowAnyException();
  }

  @Test
  void anEmptyTierList_allowsEveryTier() {
    assertThatCode(() -> policy.check(CallerRole.USER, plan("Anything", 3, false, true)))
        .doesNotThrowAnyException();
  }

  @Test
  void zeroTokens_isRefused_butAPlanWithoutTokensIsNot() {
    assertThat(refusal(CallerRole.USER, plan("Pro", 0, false, true))).isEqualTo(Reason.NO_TOKENS);
    assertThatCode(() -> policy.check(CallerRole.USER, plan("Yearly", null, false, true)))
        .doesNotThrowAnyException();
  }

  @Test
  void billingsOwnNo_isRefusedLast() {
    assertThat(refusal(CallerRole.USER, plan("Pro", 3, false, false)))
        .isEqualTo(Reason.NOT_ENTITLED);
  }

  @Test
  void aRefusal_mapsTo403_withItsReason() {
    var response =
        new GlobalExceptionHandler()
            .handlePlanRequired(new PlanRequiredException(Reason.NO_TOKENS));

    assertThat(response.status()).isEqualTo(403);
    assertThat(response.message()).isEqualTo("No tokens left");
    assertThat(response.details()).containsEntry("reason", "NO_TOKENS");
  }

  @Test
  void enabledWithoutAUrlOrKey_stopsStartup() {
    BillingConfig bad = new BillingConfig();
    bad.setEnabled(true);
    bad.setBaseUrl("http://billing");
    assertThatThrownBy(() -> invokeValidation(bad))
        .hasRootCauseInstanceOf(IllegalStateException.class);
  }

  private static void invokeValidation(BillingConfig cfg) throws Exception {
    var m = BillingConfig.class.getDeclaredMethod("requireUrlAndKeyWhenEnabled");
    m.setAccessible(true);
    m.invoke(cfg);
  }
}
