package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.service.impl.MechanicLocationWriter;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What a GPS ping costs the database. A ping inside the throttle window must touch no table: it
 * used to run an existence query in a transaction, which at one ping every couple of seconds per
 * mechanic was most of the statements this service sent. A persisted ping is one UPDATE plus the
 * active-job lookup. Boots its own context to turn Hibernate statistics on.
 */
class LocationPingQueryCountIntegrationTest extends FlowTestSupport {

  private static final double LAT = 11.5600;
  private static final double LNG = 104.9300;

  @DynamicPropertySource
  static void statistics(DynamicPropertyRegistry r) {
    r.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
  }

  @Autowired private EntityManagerFactory emf;
  @Autowired private MechanicLocationWriter locationWriter;

  @Test
  void aThrottledPing_sendsNoStatement_andAPersistedOneAtMostTwo() {
    Mechanic m = newMechanic(11.55, 104.92);
    Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();

    stats.clear();
    locationWriter.persistAndResolveSos(m.getMechanicId(), LAT, LNG, null);
    long persisted = stats.getPrepareStatementCount();

    stats.clear();
    locationWriter.persistAndResolveSos(m.getMechanicId(), LAT + 0.001, LNG, null);
    long throttled = stats.getPrepareStatementCount();

    assertThat(persisted).as("statements for a persisted ping").isLessThanOrEqualTo(2);
    assertThat(throttled).as("statements for a ping inside the throttle window").isZero();
    // The first ping really did write the row.
    assertThat(mechanicRepository.findById(m.getMechanicId()).orElseThrow().getCurrentH3Index())
        .isNotNull();
  }
}
