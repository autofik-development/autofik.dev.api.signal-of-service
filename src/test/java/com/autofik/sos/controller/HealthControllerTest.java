package com.autofik.sos.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final HealthController controller = new HealthController(jdbc);

  @Test
  void root_reportsTheServiceUp() {
    Map<String, Object> body = controller.root();
    assertThat(body).containsEntry("service", "sos").containsEntry("status", "UP");
  }

  @Test
  void healthy_reportsTheDatabaseUp_whenSelectOneWorks() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    var r = controller.healthy();
    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(r.getBody()).containsEntry("database", "UP");
  }

  /** A probe that only reads the HTTP status must see the failure. */
  @Test
  void healthy_whenTheDatabaseIsDown_answers503() {
    when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<Integer>>any()))
        .thenThrow(new DataAccessResourceFailureException("connection refused"));

    var r = controller.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(r.getBody()).containsEntry("database", "DOWN");
  }
}
