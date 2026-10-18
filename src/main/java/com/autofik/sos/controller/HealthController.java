package com.autofik.sos.controller;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class HealthController implements AutoCloseable {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  // ISO-8601 in UTC with milliseconds, as SSO's /health writes it (JavaScript's toISOString).
  // Not Instant.toString(), which drops a zero fraction and prints micros when it has them.
  private static final DateTimeFormatter ISO_UTC_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final long KAFKA_TIMEOUT_MS = 1000;

  private final JdbcTemplate jdbcTemplate;
  private final RedisConnectionFactory redisConnectionFactory;
  private final KafkaAdmin kafkaAdmin;
  private volatile Admin kafkaAdminClient;

  public HealthController(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, (RedisConnectionFactory) null, (KafkaAdmin) null);
  }

  public HealthController(
      JdbcTemplate jdbcTemplate,
      RedisConnectionFactory redisConnectionFactory,
      KafkaAdmin kafkaAdmin) {
    this.jdbcTemplate = jdbcTemplate;
    this.redisConnectionFactory = redisConnectionFactory;
    this.kafkaAdmin = kafkaAdmin;
  }

  HealthController(
      JdbcTemplate jdbcTemplate,
      RedisConnectionFactory redisConnectionFactory,
      KafkaAdmin kafkaAdmin,
      Admin kafkaAdminClient) {
    this.jdbcTemplate = jdbcTemplate;
    this.redisConnectionFactory = redisConnectionFactory;
    this.kafkaAdmin = kafkaAdmin;
    this.kafkaAdminClient = kafkaAdminClient;
  }

  @Autowired
  public HealthController(
      JdbcTemplate jdbcTemplate,
      ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider,
      ObjectProvider<KafkaAdmin> kafkaAdminProvider) {
    this(
        jdbcTemplate,
        redisConnectionFactoryProvider != null
            ? redisConnectionFactoryProvider.getIfAvailable()
            : null,
        kafkaAdminProvider != null ? kafkaAdminProvider.getIfAvailable() : null);
  }

  @GetMapping("/")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> root() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("service", "sos");
    response.put("status", "UP");
    response.put("timestamp", LocalDateTime.now().format(FORMATTER));
    return response;
  }

  // The envelope SSO's GET /health answers with (ApiResponse in the SSO repo), so a monitor reads
  // both services one way: {"status":"success","message":"ok","code":"HEALTH_CHECK_SUCCESS",
  // "data":{...}} while serving, {"status":"error",...,"details":{...}} when not.
  //
  // The status code keeps SOS's own rule: 503 only when the database is down, so a probe that only
  // reads the status code stops routing here. Redis and Kafka degradations are "degraded" with 200,
  // as SOS falls back gracefully. A check's exception text never reaches the body: it can name
  // internal hosts, and this endpoint is public.
  @GetMapping({"/healthy", "/health"})
  public ResponseEntity<Map<String, Object>> healthy() {
    Check database = Check.run(this::checkDatabase);
    Check redis = Check.run(this::checkRedis);
    Check kafka = Check.run(this::checkKafka);
    boolean serving = database.up();
    boolean degraded = redis.down() || kafka.down();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("status", !serving ? "down" : degraded ? "degraded" : "ok");
    database.report(data, "database");
    redis.report(data, "redis");
    kafka.report(data, "kafka");
    data.put("timestamp", ISO_UTC_MILLIS.format(Instant.now()));

    Map<String, Object> response = new LinkedHashMap<>();
    if (!serving) {
      response.put("status", "error");
      response.put("message", "Service unavailable");
      response.put("code", "HEALTH_DOWN");
      response.put("details", data);
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
    response.put("status", "success");
    response.put("message", degraded ? "degraded" : "ok");
    response.put("code", degraded ? "HEALTH_DEGRADED" : "HEALTH_CHECK_SUCCESS");
    response.put("data", data);
    return ResponseEntity.ok(response);
  }

  /** One component's check and how long it took. {@code status} is UP, DOWN or UNKNOWN. */
  private record Check(String status, long latencyMs) {

    static Check run(Supplier<String> check) {
      long start = System.nanoTime();
      String status = check.get();
      return new Check(status, (System.nanoTime() - start) / 1_000_000);
    }

    boolean up() {
      return "UP".equals(status);
    }

    boolean down() {
      return "DOWN".equals(status);
    }

    // SSO's words: "connected" or "disconnected", plus "unknown" for a component this instance has
    // no client for. The latency only when the component answered, as SSO reports its Redis ping.
    void report(Map<String, Object> data, String name) {
      data.put(name, up() ? "connected" : down() ? "disconnected" : "unknown");
      if (up()) {
        data.put(name + "LatencyMs", latencyMs);
      }
    }
  }

  String checkDatabase() {
    if (jdbcTemplate == null) {
      return "UNKNOWN";
    }
    try {
      jdbcTemplate.queryForObject("SELECT 1", Integer.class);
      return "UP";
    } catch (Exception e) {
      return "DOWN";
    }
  }

  String checkRedis() {
    if (redisConnectionFactory == null) {
      return "UNKNOWN";
    }
    RedisConnection connection = null;
    try {
      connection = RedisConnectionUtils.getConnection(redisConnectionFactory);
      String pong = connection.ping();
      return "PONG".equalsIgnoreCase(pong) || pong != null ? "UP" : "DOWN";
    } catch (Exception e) {
      return "DOWN";
    } finally {
      if (connection != null) {
        RedisConnectionUtils.releaseConnection(connection, redisConnectionFactory);
      }
    }
  }

  String checkKafka() {
    if (kafkaAdminClient == null && kafkaAdmin == null) {
      return "UNKNOWN";
    }
    Admin admin = getOrCreateKafkaAdmin();
    if (admin == null) {
      return "DOWN";
    }
    try {
      admin
          .describeCluster(new DescribeClusterOptions().timeoutMs((int) KAFKA_TIMEOUT_MS))
          .clusterId()
          .get(KAFKA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      return "UP";
    } catch (Exception e) {
      return "DOWN";
    }
  }

  private synchronized Admin getOrCreateKafkaAdmin() {
    if (kafkaAdminClient == null && kafkaAdmin != null) {
      try {
        Map<String, Object> props = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) KAFKA_TIMEOUT_MS);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) KAFKA_TIMEOUT_MS);
        kafkaAdminClient = Admin.create(props);
      } catch (Exception e) {
        log.warn("Failed to create Kafka Admin client for health checks: {}", e.getMessage());
      }
    }
    return kafkaAdminClient;
  }

  @PreDestroy
  @Override
  public void close() {
    if (kafkaAdminClient != null) {
      try {
        kafkaAdminClient.close(Duration.ofSeconds(1));
      } catch (Exception e) {
        log.debug("Error closing Kafka Admin client: {}", e.getMessage());
      }
    }
  }
}
