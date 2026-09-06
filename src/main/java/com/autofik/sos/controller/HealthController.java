package com.autofik.sos.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HealthController {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private final JdbcTemplate jdbcTemplate;

  @GetMapping("/")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> root() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("service", "sos");
    response.put("status", "UP");
    response.put("timestamp", LocalDateTime.now().format(FORMATTER));
    return response;
  }

  @GetMapping("/healthy")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> healthy() {
    String dbStatus;
    try {
      jdbcTemplate.queryForObject("SELECT 1", Integer.class);
      dbStatus = "UP";
    } catch (Exception e) {
      dbStatus = "DOWN";
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("status", "SUCCESS");
    response.put("database", dbStatus);
    response.put("timestamp", LocalDateTime.now().format(FORMATTER));
    return response;
  }
}
