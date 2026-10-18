package com.autofik.sos.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {

  // SSO's /health timestamp: ISO-8601 in UTC, always three fraction digits.
  private static final String ISO_UTC_MILLIS =
      "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
  private final RedisConnection redisConn = mock(RedisConnection.class);
  private final Admin kafkaAdmin = mock(Admin.class);

  private final HealthController fullController =
      new HealthController(jdbc, redisFactory, null, kafkaAdmin);
  private final HealthController simpleController = new HealthController(jdbc);

  @Test
  void root_reportsTheServiceUp() {
    Map<String, Object> body = simpleController.root();
    assertThat(body).containsEntry("service", "sos").containsEntry("status", "UP");
  }

  @Test
  void healthy_reportsEveryComponentConnected_inSsosEnvelope_whenEverythingWorks() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    redisAnswers();
    kafkaAnswers();

    ResponseEntity<Map<String, Object>> r = fullController.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(r.getBody().keySet()).containsExactly("status", "message", "code", "data");
    assertThat(r.getBody())
        .containsEntry("status", "success")
        .containsEntry("message", "ok")
        .containsEntry("code", "HEALTH_CHECK_SUCCESS");
    Map<String, Object> data = section(r, "data");
    assertThat(data.keySet())
        .containsExactly(
            "status",
            "database",
            "databaseLatencyMs",
            "redis",
            "redisLatencyMs",
            "kafka",
            "kafkaLatencyMs",
            "timestamp");
    assertThat(data)
        .containsEntry("status", "ok")
        .containsEntry("database", "connected")
        .containsEntry("redis", "connected")
        .containsEntry("kafka", "connected");
    assertThat((Long) data.get("redisLatencyMs")).isNotNegative();
    assertThat((String) data.get("timestamp")).matches(ISO_UTC_MILLIS);
  }

  @Test
  void healthy_whenTheDatabaseIsDown_answers503_inTheErrorEnvelope() {
    when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<Integer>>any()))
        .thenThrow(new DataAccessResourceFailureException("connection refused to pg-primary:5432"));
    redisAnswers();
    kafkaAnswers();

    ResponseEntity<Map<String, Object>> r = fullController.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(r.getBody().keySet()).containsExactly("status", "message", "code", "details");
    assertThat(r.getBody())
        .containsEntry("status", "error")
        .containsEntry("message", "Service unavailable")
        .containsEntry("code", "HEALTH_DOWN");
    assertThat(section(r, "details"))
        .containsEntry("status", "down")
        .containsEntry("database", "disconnected")
        .containsEntry("redis", "connected")
        .containsEntry("kafka", "connected")
        .doesNotContainKey("databaseLatencyMs");
    assertThat(r.getBody().toString()).doesNotContain("pg-primary");
  }

  @Test
  void healthy_whenRedisIsDown_answers200_andReportsDegraded() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    when(redisFactory.getConnection())
        .thenThrow(new RedisConnectionFailureException("connection refused to redis-0:6379"));
    kafkaAnswers();

    ResponseEntity<Map<String, Object>> r = fullController.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(r.getBody())
        .containsEntry("status", "success")
        .containsEntry("message", "degraded")
        .containsEntry("code", "HEALTH_DEGRADED");
    assertThat(section(r, "data"))
        .containsEntry("status", "degraded")
        .containsEntry("database", "connected")
        .containsEntry("redis", "disconnected")
        .containsEntry("kafka", "connected")
        .doesNotContainKey("redisLatencyMs");
    assertThat(r.getBody().toString()).doesNotContain("redis-0");
  }

  @Test
  void healthy_whenKafkaIsDown_answers200_andReportsDegraded() throws Exception {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    redisAnswers();

    DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
    @SuppressWarnings("unchecked")
    KafkaFuture<String> failingFuture = mock(KafkaFuture.class);
    when(failingFuture.get(
            org.mockito.ArgumentMatchers.anyLong(), any(java.util.concurrent.TimeUnit.class)))
        .thenThrow(new TimeoutException("broker timeout"));
    when(clusterResult.clusterId()).thenReturn(failingFuture);
    when(kafkaAdmin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

    ResponseEntity<Map<String, Object>> r = fullController.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(r.getBody())
        .containsEntry("status", "success")
        .containsEntry("code", "HEALTH_DEGRADED");
    assertThat(section(r, "data"))
        .containsEntry("status", "degraded")
        .containsEntry("database", "connected")
        .containsEntry("redis", "connected")
        .containsEntry("kafka", "disconnected")
        .doesNotContainKey("kafkaLatencyMs");
  }

  @Test
  void healthy_whenOptionalComponentsAreNull_reportsUnknown_andStatusOk() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

    ResponseEntity<Map<String, Object>> r = simpleController.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(r.getBody()).containsEntry("code", "HEALTH_CHECK_SUCCESS");
    assertThat(section(r, "data"))
        .containsEntry("status", "ok")
        .containsEntry("database", "connected")
        .containsEntry("redis", "unknown")
        .containsEntry("kafka", "unknown")
        .doesNotContainKeys("redisLatencyMs", "kafkaLatencyMs");
  }

  @Test
  void close_closesAdminClientSafely() {
    fullController.close();
    verify(kafkaAdmin).close(any(Duration.class));
  }

  private void redisAnswers() {
    when(redisFactory.getConnection()).thenReturn(redisConn);
    when(redisConn.ping()).thenReturn("PONG");
  }

  private void kafkaAnswers() {
    DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
    when(clusterResult.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-1"));
    when(kafkaAdmin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(ResponseEntity<Map<String, Object>> r, String key) {
    return (Map<String, Object>) r.getBody().get(key);
  }
}
