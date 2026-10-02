package com.autofik.sos.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  /** A duplicate insert is the caller's conflict (409), and must not leak the constraint name. */
  @Test
  void dataIntegrityViolation_is409_withoutLeakingTheConstraint() {
    var cause =
        new RuntimeException("duplicate key value violates unique constraint \"uk_feedback_sos\"");

    ErrorResponse response =
        handler.handleDataIntegrity(
            new DataIntegrityViolationException("could not execute", cause));

    assertThat(response.status()).isEqualTo(409);
    assertThat(response.message()).isEqualTo("The request conflicts with existing data");
    assertThat(response.message()).doesNotContain("uk_feedback_sos");
  }
}
