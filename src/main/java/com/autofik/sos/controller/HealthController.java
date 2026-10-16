package com.autofik.sos.controller;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

  // 503 when the database is down so a probe that only reads the status code stops routing here.
  // Redis and Kafka degradations report DEGRADED with 200, as SOS falls back gracefully.
  @GetMapping({"/healthy", "/health"})
  public ResponseEntity<Map<String, Object>> healthy() {
    String dbStatus = checkDatabase();
    String redisStatus = checkRedis();
    String kafkaStatus = checkKafka();

    String overallStatus;
    if (!"UP".equals(dbStatus)) {
      overallStatus = "DOWN";
    } else if ("DOWN".equals(redisStatus) || "DOWN".equals(kafkaStatus)) {
      overallStatus = "DEGRADED";
    } else {
      overallStatus = "UP";
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("status", overallStatus);
    response.put("database", dbStatus);
    response.put("redis", redisStatus);
    response.put("kafka", kafkaStatus);
    response.put("timestamp", LocalDateTime.now().format(FORMATTER));

    HttpStatus httpStatus = "UP".equals(dbStatus) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
    return ResponseEntity.status(httpStatus).body(response);
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
