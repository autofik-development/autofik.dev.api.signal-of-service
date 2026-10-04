package com.autofik.sos.integration;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.service.GoogleMapsService;
import com.autofik.sos.service.MedusaService;
import com.autofik.sos.service.OneSignalService;
import com.github.dockerjava.api.command.CreateContainerCmd;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the whole app against real Postgres/PostGIS, Redis and Kafka in Docker. Run with {@code
 * ./mvnw test -Pintegration}; these are tagged and skipped by a normal build, which needs no
 * Docker.
 *
 * <p>Only the services the app calls over the internet are replaced (Medusa, Google, OneSignal), so
 * nothing here can reach a real account or bill anyone. The containers start once per JVM and are
 * shared by every subclass; each test makes its own users and mechanics with fresh ids instead of
 * cleaning up after itself.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

  // Same images the dev infra runs (see CLAUDE.md), so this tests what really runs.
  // Set IT_DOCKER_NETWORK=<name> when the tests themselves run inside a container on that Docker
  // network (e.g. on a machine where the JVM cannot reach Docker's published ports). The containers
  // then join the same network and are addressed by alias and internal port instead.
  private static final String NETWORK = System.getenv("IT_DOCKER_NETWORK");
  private static final boolean IN_NETWORK = NETWORK != null && !NETWORK.isBlank();

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          // Not the default port wait: that one connects from the test JVM to the published port.
          .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\n", 1));
  static final ConfluentKafkaContainer KAFKA =
      new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

  static {
    if (IN_NETWORK) {
      // Named containers, not network aliases: Testcontainers ignores aliases unless it created the
      // network itself, but Docker resolves a container's name on any user-defined network.
      POSTGRES
          .withNetworkMode(NETWORK)
          .withCreateContainerCmdModifier(new ContainerName("it-postgres"));
      REDIS.withNetworkMode(NETWORK).withCreateContainerCmdModifier(new ContainerName("it-redis"));
      KAFKA.withNetworkMode(NETWORK).withCreateContainerCmdModifier(new ContainerName("it-kafka"));
    }
    Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
  }

  /**
   * A named class, not a lambda, on purpose. The containers start on other threads while this
   * class's static initializer waits for them, and a lambda defined here cannot run until that
   * initializer finishes: the two wait on each other forever.
   */
  private static final class ContainerName implements Consumer<CreateContainerCmd> {
    private final String name;

    ContainerName(String name) {
      this.name = name;
    }

    @Override
    public void accept(CreateContainerCmd cmd) {
      cmd.withName(name).withHostName(name);
    }
  }

  static String dbHost() {
    return IN_NETWORK ? "it-postgres" : POSTGRES.getHost();
  }

  static int dbPort() {
    return IN_NETWORK ? 5432 : POSTGRES.getMappedPort(5432);
  }

  static String redisHost() {
    return IN_NETWORK ? "it-redis" : REDIS.getHost();
  }

  static int redisPort() {
    return IN_NETWORK ? 6379 : REDIS.getMappedPort(6379);
  }

  /** The broker's address as the app and the tests should use it. */
  static String kafkaBootstrap() {
    return IN_NETWORK ? "it-kafka:9093" : KAFKA.getBootstrapServers();
  }

  /** Connection-pool size; a test that wants to provoke exhaustion lowers it. */
  protected static final int DB_POOL_MAX = Integer.getInteger("it.db.pool.max", 5);

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry r) {
    // application.yaml resolves these from the environment; supplying them as properties does the
    // same job without touching the machine's real .env.
    r.add("SPRING_PROFILES_ACTIVE", () -> "test");
    r.add("SERVER_PORT", () -> "0");
    r.add("DB_HOST", IntegrationTestBase::dbHost);
    r.add("DB_PORT", () -> String.valueOf(dbPort()));
    r.add("DB_NAME", POSTGRES::getDatabaseName);
    r.add("DB_USERNAME", POSTGRES::getUsername);
    r.add("DB_PASSWORD", POSTGRES::getPassword);
    r.add("REDIS_TIMEOUT_MS", () -> "1000"); // a local .env must not change what the tests measure
    r.add("DB_POOL_MAX", () -> String.valueOf(DB_POOL_MAX));
    r.add("DB_POOL_MIN_IDLE", () -> "1");
    r.add("DB_CONNECTION_TIMEOUT_MS", () -> "2000");
    r.add("REDIS_HOST", IntegrationTestBase::redisHost);
    r.add("REDIS_PORT", () -> String.valueOf(redisPort()));
    r.add("KAFKA_BOOTSTRAP_SERVERS", IntegrationTestBase::kafkaBootstrap);
    r.add("KAFKA_CONSUMER_GROUP_ID", () -> "it-" + UUID.randomUUID());
    r.add("WEBSOCKET_ALLOWED_ORIGINS", () -> "*");
    // Never used: the external services below are mocked. They only have to be non-empty.
    r.add("GOOGLE_MAPS_API_KEY", () -> "test");
    r.add("MEDUSA_BASE_URL", () -> "http://localhost:1");
    r.add("MEDUSA_PUBLIC_KEY", () -> "pk_test");
    r.add("ONESIGNAL_APP_ID", () -> "test");
    r.add("ONESIGNAL_API_KEY", () -> "test");
    // Without this, application.yaml falls back to the real external S3 host. A dead local port
    // makes a test that forgot its S3 stand-in fail at once instead of reaching that host.
    // SosMediaIntegrationTest registers its own endpoint on top of this.
    r.add("S3_ENDPOINT", () -> "http://localhost:1");
    r.add("S3_ACCESS_KEY", () -> "test");
    r.add("S3_SECRET_KEY", () -> "test");
  }

  @MockitoBean protected MedusaService medusaService;
  @MockitoBean protected GoogleMapsService googleMapsService;
  @MockitoBean protected OneSignalService oneSignalService;

  @LocalServerPort protected int port;

  @Autowired protected UserRepository userRepository;
  @Autowired protected VehicleRepository vehicleRepository;
  @Autowired protected MechanicRepository mechanicRepository;

  private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

  // ── data ───────────────────────────────────────────────────────────────────

  protected record Driver(User user, Vehicle vehicle) {}

  /** A driver and their car, with ids no other test shares. */
  protected Driver newDriver() {
    String unique = UUID.randomUUID().toString();
    User user =
        userRepository.save(
            User.builder()
                .userId(UUID.randomUUID())
                .name("Driver " + unique.substring(0, 8))
                .phone("+855" + Math.abs(unique.hashCode()) + unique.substring(0, 4))
                .build());
    Vehicle vehicle =
        vehicleRepository.save(
            Vehicle.builder()
                .id(UUID.randomUUID())
                .carBrand("Toyota")
                .carModel("Camry")
                .user(user)
                .build());
    return new Driver(user, vehicle);
  }

  /** A mechanic parked at the given point, for the live-location and matching paths. */
  protected Mechanic newMechanic(double lat, double lng) {
    String unique = UUID.randomUUID().toString();
    return mechanicRepository.save(
        Mechanic.builder()
            .mechanicId(UUID.randomUUID())
            .fullName("Mech " + unique.substring(0, 8))
            .phoneNumber("+855" + Math.abs(unique.hashCode()))
            .email(unique.substring(0, 8) + "@it.test")
            .currentLocation(GEO.createPoint(new Coordinate(lng, lat)))
            .build());
  }

  // ── gateway identity: the headers the real gateway sends ───────────────────

  protected static String userContext(UUID id) {
    return "{\"user\":{\"uuid\":\""
        + id
        + "\",\"role\":\"USER\",\"otpVerify\":true,\"profileVerify\":true,"
        + "\"tokenStatus\":\"active\",\"isLogout\":false}}";
  }

  protected static String mechanicContext(UUID id) {
    return "{\"mechanic\":{\"uuid\":\""
        + id
        + "\",\"otpVerify\":true,\"profileVerify\":true,\"tokenStatus\":\"active\","
        + "\"isLogout\":false}}";
  }

  // ── probing the running app ────────────────────────────────────────────────

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  /** Round-trip time of {@code GET /healthy}, or -1 if it did not answer 200 within the limit. */
  protected long healthProbeMillis(Duration limit) {
    long start = System.nanoTime();
    try {
      HttpResponse<Void> response =
          HTTP.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/healthy"))
                  .timeout(limit)
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200 ? (System.nanoTime() - start) / 1_000_000 : -1;
    } catch (Exception e) {
      return -1;
    }
  }
}
