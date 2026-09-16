package com.autofik.sos.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class SosRequestTest {

  // biddingExpiresAt and bidDecisionExpiresAt must stay independently computed — conflating them
  // was the bug being fixed here, so this pins both durations separately even though they're
  // numerically equal today.
  @Test
  void onCreate_setsBiddingAndBidDecisionDeadlinesIndependently() {
    SosRequest sos = new SosRequest();

    sos.onCreate();

    assertThat(sos.getBiddingExpiresAt())
        .isCloseTo(
            sos.getCreatedAt().plusSeconds(SosRequest.BIDDING_WINDOW_SECONDS),
            org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
    assertThat(sos.getBidDecisionExpiresAt())
        .isCloseTo(
            sos.getCreatedAt().plusSeconds(SosRequest.BID_DECISION_WINDOW_SECONDS),
            org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
  }
}
