package com.autofik.sos.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

/** How each dependency check answers, including when there is no client for it at all. */
class HealthControllerChecksTest {

  private static Admin adminAnswering(KafkaFuture<String> clusterId) {
    Admin admin = mock(Admin.class);
    DescribeClusterResult result = mock(DescribeClusterResult.class);
    when(result.clusterId()).thenReturn(clusterId);
    when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(result);
    return admin;
  }

  @Test
  @SuppressWarnings("unchecked")
  void theSpringConstructor_acceptsMissingProviders_andProvidersWithNothingInside() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectProvider<RedisConnectionFactory> noRedis = mock(ObjectProvider.class);
    ObjectProvider<KafkaAdmin> noKafka = mock(ObjectProvider.class);

    HealthController nullProviders =
        new HealthController(
            jdbc, (ObjectProvider<RedisConnectionFactory>) null, (ObjectProvider<KafkaAdmin>) null);
    HealthController emptyProviders = new HealthController(jdbc, noRedis, noKafka);

    assertThat(nullProviders.checkRedis()).isEqualTo("UNKNOWN");
    assertThat(emptyProviders.checkKafka()).isEqualTo("UNKNOWN");
  }

  @Test
  void noDatabaseClient_isUnknown_aWorkingOneIsUp_aFailingOneIsDown() {
    assertThat(new HealthController(null, null, null, null).checkDatabase()).isEqualTo("UNKNOWN");

    JdbcTemplate working = mock(JdbcTemplate.class);
    assertThat(new HealthController(working, null, null, null).checkDatabase()).isEqualTo("UP");

    JdbcTemplate failing = mock(JdbcTemplate.class);
    when(failing.queryForObject("SELECT 1", Integer.class)).thenThrow(new RuntimeException("x"));
    assertThat(new HealthController(failing, null, null, null).checkDatabase()).isEqualTo("DOWN");
  }

  @Test
  void redis_answersUpOnAnyPong_downOnSilence_orOnAnError() {
    RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    RedisConnection connection = mock(RedisConnection.class);
    when(factory.getConnection()).thenReturn(connection);
    HealthController controller = new HealthController(null, factory, null, null);

    when(connection.ping()).thenReturn("PONG");
    assertThat(controller.checkRedis()).isEqualTo("UP");

    when(connection.ping()).thenReturn(null);
    assertThat(controller.checkRedis()).isEqualTo("DOWN");

    when(connection.ping()).thenThrow(new RuntimeException("timeout"));
    assertThat(controller.checkRedis()).isEqualTo("DOWN");

    when(factory.getConnection()).thenThrow(new RuntimeException("refused"));
    assertThat(controller.checkRedis()).isEqualTo("DOWN");
  }

  @Test
  void kafka_withAClient_isUpWhenTheClusterAnswers_downWhenItDoesNot() {
    KafkaFuture<String> ok = KafkaFuture.completedFuture("cluster-1");
    assertThat(new HealthController(null, null, null, adminAnswering(ok)).checkKafka())
        .isEqualTo("UP");

    KafkaFutureImpl<String> failed = new KafkaFutureImpl<>();
    failed.completeExceptionally(new RuntimeException("no broker"));
    assertThat(new HealthController(null, null, null, adminAnswering(failed)).checkKafka())
        .isEqualTo("DOWN");
  }

  @Test
  void kafka_withOnlyAdminProperties_thatCannotBuildAClient_isDown() {
    KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
    when(kafkaAdmin.getConfigurationProperties()).thenReturn(Map.of()); // no bootstrap.servers

    assertThat(new HealthController(null, null, kafkaAdmin, null).checkKafka()).isEqualTo("DOWN");
  }

  @Test
  void kafka_withAdminProperties_buildsAClientOnce_andClosesIt() {
    KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
    // A client builds without connecting; the describe call then times out, which is "down".
    when(kafkaAdmin.getConfigurationProperties())
        .thenReturn(Map.of("bootstrap.servers", "localhost:1"));
    HealthController controller = new HealthController(null, null, kafkaAdmin, null);

    assertThat(controller.checkKafka()).isEqualTo("DOWN");
    assertThat(controller.checkKafka()).isEqualTo("DOWN"); // reuses the client it built

    controller.close();
  }

  @Test
  void close_closesTheKafkaClient_andSurvivesOneThatFailsToClose() {
    Admin admin = mock(Admin.class);
    new HealthController(null, null, null, admin).close();
    verify(admin).close(Duration.ofSeconds(1));

    Admin broken = mock(Admin.class);
    doThrow(new RuntimeException("already closed")).when(broken).close(any(Duration.class));
    new HealthController(null, null, null, broken).close(); // must not throw

    new HealthController(null, null, null, null).close(); // nothing to close
  }

  @Test
  void redis_anyNonNullReplyCountsAsUp() {
    RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    RedisConnection connection = mock(RedisConnection.class);
    when(factory.getConnection()).thenReturn(connection);
    when(connection.ping()).thenReturn("OK");

    assertThat(new HealthController(null, factory, null, null).checkRedis()).isEqualTo("UP");
  }
}
