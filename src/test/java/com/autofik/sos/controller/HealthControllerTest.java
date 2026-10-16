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
  void healthy_reportsAllComponentsUp_whenEverythingWorks() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    when(redisFactory.getConnection()).thenReturn(redisConn);
    when(redisConn.ping()).thenReturn("PONG");

    DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
    when(clusterResult.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-1"));
    when(kafkaAdmin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

    ResponseEntity<Map<String, Object>> r = fullController.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(r.getBody())
        .containsEntry("status", "UP")
        .containsEntry("database", "UP")
        .containsEntry("redis", "UP")
        .containsEntry("kafka", "UP");
  }

  @Test
  void healthy_whenTheDatabaseIsDown_answers503_andReportsDown() {
    when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<Integer>>any()))
        .thenThrow(new DataAccessResourceFailureException("connection refused"));
    when(redisFactory.getConnection()).thenReturn(redisConn);
    when(redisConn.ping()).thenReturn("PONG");

    DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
    when(clusterResult.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-1"));
    when(kafkaAdmin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

    ResponseEntity<Map<String, Object>> r = fullController.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(r.getBody())
        .containsEntry("status", "DOWN")
        .containsEntry("database", "DOWN")
        .containsEntry("redis", "UP")
        .containsEntry("kafka", "UP");
  }

  @Test
  void healthy_whenRedisIsDown_answers200_andReportsDegraded() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    when(redisFactory.getConnection())
        .thenThrow(new RedisConnectionFailureException("connection refused"));

    DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
    when(clusterResult.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-1"));
    when(kafkaAdmin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

    ResponseEntity<Map<String, Object>> r = fullController.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(r.getBody())
        .containsEntry("status", "DEGRADED")
        .containsEntry("database", "UP")
        .containsEntry("redis", "DOWN")
        .containsEntry("kafka", "UP");
  }

  @Test
  void healthy_whenKafkaIsDown_answers200_andReportsDegraded() throws Exception {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    when(redisFactory.getConnection()).thenReturn(redisConn);
    when(redisConn.ping()).thenReturn("PONG");

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
        .containsEntry("status", "DEGRADED")
        .containsEntry("database", "UP")
        .containsEntry("redis", "UP")
        .containsEntry("kafka", "DOWN");
  }

  @Test
  void healthy_whenOptionalComponentsAreNull_reportsUnknown_andStatusUp() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

    ResponseEntity<Map<String, Object>> r = simpleController.healthy();

    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(r.getBody())
        .containsEntry("status", "UP")
        .containsEntry("database", "UP")
        .containsEntry("redis", "UNKNOWN")
        .containsEntry("kafka", "UNKNOWN");
  }

  @Test
  void close_closesAdminClientSafely() {
    fullController.close();
    verify(kafkaAdmin).close(any(Duration.class));
  }
}
