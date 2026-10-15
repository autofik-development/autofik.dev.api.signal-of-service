package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.util.Geo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the custom and native repository queries against real PostGIS, with the schema built by the
 * Flyway migrations and Hibernate only validating it, as in production. Tagged {@code integration}:
 * needs Docker, runs with {@code ./mvnw verify -Pintegration}. Each test rolls back, so tests need
 * no cleanup and can share the one container.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class RepositoryTestBase {

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    // application.yaml resolves these placeholders from the environment.
    r.add("SPRING_PROFILES_ACTIVE", () -> "test");
    r.add("DB_HOST", POSTGRES::getHost);
    r.add("DB_PORT", () -> String.valueOf(POSTGRES.getMappedPort(5432)));
    r.add("DB_NAME", POSTGRES::getDatabaseName);
    r.add("DB_USERNAME", POSTGRES::getUsername);
    r.add("DB_PASSWORD", POSTGRES::getPassword);
    r.add("DB_POOL_MAX", () -> "3");
    r.add("DB_POOL_MIN_IDLE", () -> "1");
    // The test profile builds the schema from the entities; here the migrations must build it, so a
    // migration that drifts from the entities fails these tests.
    r.add("spring.flyway.enabled", () -> "true");
    r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  /** Phnom Penh, where every fixture below is placed unless a test moves it. */
  static final double LAT = 11.5564;

  static final double LNG = 104.9282;

  @Autowired TestEntityManager em;

  static Point point(double lat, double lng) {
    return Geo.WGS84.createPoint(new Coordinate(lng, lat));
  }

  User user() {
    String unique = UUID.randomUUID().toString();
    return em.persistAndFlush(
        User.builder()
            .userId(UUID.randomUUID())
            .name("Driver " + unique.substring(0, 8))
            .phone("+855" + unique.substring(0, 12))
            .build());
  }

  Vehicle vehicle(User owner) {
    return em.persistAndFlush(
        Vehicle.builder().id(UUID.randomUUID().toString()).carBrand("Toyota").user(owner).build());
  }

  /** A mechanic at the point, available, in the H3 cell given (may be null), with one skill row. */
  Mechanic mechanic(double lat, double lng, String h3Cell, String... skillTags) {
    String unique = UUID.randomUUID().toString();
    Mechanic m =
        em.persistAndFlush(
            Mechanic.builder()
                .mechanicId(UUID.randomUUID())
                .fullName("Mech " + unique.substring(0, 8))
                .phoneNumber("+855" + unique.substring(0, 12))
                .email(unique.substring(0, 8) + "@repo.test")
                .currentLocation(point(lat, lng))
                .currentH3Index(h3Cell)
                .build());
    if (skillTags.length > 0) {
      em.persistAndFlush(MechanicSkill.builder().mechanic(m).skillTags(skillTags).build());
    }
    return m;
  }

  SosRequest sos(User owner, SosStatus status, String problemType, double lat, double lng) {
    return em.persistAndFlush(
        SosRequest.builder()
            .problemType(problemType)
            .status(status)
            .location(point(lat, lng))
            .biddingExpiresAt(LocalDateTime.now().plusMinutes(10))
            .user(owner)
            .vehicle(vehicle(owner))
            .build());
  }

  BidSos bid(SosRequest sos, Mechanic mechanic, BidStatus status) {
    return em.persistAndFlush(
        BidSos.builder()
            .price(BigDecimal.TEN)
            .status(status)
            .sosRequest(sos)
            .mechanic(mechanic)
            .respondedAt(status == BidStatus.PENDING ? null : LocalDateTime.now())
            .build());
  }
}
