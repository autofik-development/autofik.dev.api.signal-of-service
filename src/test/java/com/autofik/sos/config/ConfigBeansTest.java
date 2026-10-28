package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.autofik.sos.redis.MechanicLocationRedisListener;
import com.onesignal.client.api.DefaultApi;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** The @Bean methods and small components that no other unit test builds. */
class ConfigBeansTest {

  @Test
  void s3Config_buildsAClientAndAPresigner_forTheConfiguredEndpoint() {
    S3Config config = new S3Config();
    ReflectionTestUtils.setField(config, "endpoint", "http://localhost:9000");
    ReflectionTestUtils.setField(config, "accessKey", "key");
    ReflectionTestUtils.setField(config, "secretKey", "secret");
    ReflectionTestUtils.setField(config, "region", "us-east-1");

    try (S3Client client = config.s3Client();
        S3Presigner presigner = config.s3Presigner()) {
      assertThat(client).isNotNull();
      assertThat(presigner).isNotNull();
    }
  }

  @Test
  void databaseStartupLogger_readsTheConnectionMetadata_andClosesTheConnection() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData meta = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(meta);
    when(meta.getURL()).thenReturn("jdbc:postgresql://db/sos");
    when(meta.getUserName()).thenReturn("sos");

    ApplicationRunner runner = new DatabaseStartupLogger(dataSource).logDatabaseConnection();
    runner.run(null);

    org.mockito.Mockito.verify(connection).close();
  }

  @Test
  void databaseStartupLogger_aFailedConnection_isLogged_notThrown() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(new SQLException("refused"));

    ApplicationRunner runner = new DatabaseStartupLogger(dataSource).logDatabaseConnection();

    runner.run(null); // must not throw: a database blip at boot is reported, not fatal
  }

  @Test
  void schedulerConfig_buildsTheExpirySchedulerAndTheHeartbeatScheduler() {
    SchedulerConfig config = new SchedulerConfig();
    ReflectionTestUtils.setField(config, "poolSize", 3);

    ThreadPoolTaskScheduler expiry = (ThreadPoolTaskScheduler) config.taskScheduler();
    ThreadPoolTaskScheduler heartbeat = (ThreadPoolTaskScheduler) config.heartbeatTaskScheduler();

    assertThat(expiry.getThreadNamePrefix()).isEqualTo("sos-expiry-");
    assertThat(heartbeat.getThreadNamePrefix()).isEqualTo("stomp-heartbeat");
  }

  @Test
  void redisLocationListenerConfig_registersTheListenerOnTheLocationChannels() {
    RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    MechanicLocationRedisListener listener = mock(MechanicLocationRedisListener.class);

    RedisMessageListenerContainer container =
        new RedisLocationListenerConfig(factory, listener).redisLocationListenerContainer();

    assertThat(container).isNotNull();
    assertThat(container.getConnectionFactory()).isSameAs(factory);
  }

  @Test
  void oneSignalConfig_buildsTheApiClient_withTheConfiguredKey() {
    OneSignalConfig config = new OneSignalConfig();
    ReflectionTestUtils.setField(config, "apiKey", "test-key");

    DefaultApi api = config.oneSignalApi();

    assertThat(api).isNotNull();
  }

  @Test
  void bidCommandExecutor_runsTheCommandOnAPoolThread_andShutsDown() throws Exception {
    BidCommandExecutor executor = new BidCommandExecutor(2, 2);
    CountDownLatch ran = new CountDownLatch(1);
    AtomicInteger thread = new AtomicInteger();

    executor.execute(
        () -> {
          thread.set(Thread.currentThread().getName().startsWith("bid-command-") ? 1 : 0);
          ran.countDown();
        });

    assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(thread.get()).isEqualTo(1);
    executor.destroy();
  }

  @Test
  void bidCommandExecutor_aFullQueue_isRefusedRatherThanQueuedWithoutLimit() throws Exception {
    BidCommandExecutor executor = new BidCommandExecutor(1, 1);
    CountDownLatch release = new CountDownLatch(1);
    Runnable blocker =
        () -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    try {
      executor.execute(blocker); // takes the one thread
      executor.execute(blocker); // takes the one queue slot
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.execute(blocker))
          .isInstanceOf(RejectedExecutionException.class);
    } finally {
      release.countDown();
      executor.destroy();
    }
  }

  @Test
  void bidCommandExecutor_inline_runsOnTheCallingThread() {
    AtomicInteger calls = new AtomicInteger();
    String caller = Thread.currentThread().getName();
    StringBuilder ranOn = new StringBuilder();

    BidCommandExecutor.inline()
        .execute(
            () -> {
              calls.incrementAndGet();
              ranOn.append(Thread.currentThread().getName());
            });

    assertThat(calls.get()).isEqualTo(1);
    assertThat(ranOn.toString()).isEqualTo(caller);
  }
}
