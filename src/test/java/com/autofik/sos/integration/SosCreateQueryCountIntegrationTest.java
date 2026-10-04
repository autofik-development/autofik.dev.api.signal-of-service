package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.repository.MechanicSkillRepository;
import com.uber.h3core.H3Core;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Creating an SOS must cost a fixed number of SQL statements, however many mechanics it reaches.
 * Loading each matched mechanic as an entity pulled its inverse @OneToOne rating with one SELECT
 * per mechanic, twice per geo query: 5,532 statements per create against the load-test seed, which
 * made create take ~1 s and drained the connection pool under load. Boots its own context to turn
 * Hibernate statistics on.
 */
class SosCreateQueryCountIntegrationTest extends FlowTestSupport {

  private static final int MECHANICS = 40;

  @DynamicPropertySource
  static void statistics(DynamicPropertyRegistry r) {
    r.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
  }

  @Autowired private EntityManagerFactory emf;
  @Autowired private MechanicSkillRepository mechanicSkillRepository;

  @Test
  void createSos_statementCount_doesNotGrowWithTheMechanicsItReaches() throws Exception {
    H3Core h3 = H3Core.newInstance();
    for (int i = 0; i < MECHANICS; i++) {
      double lat = 11.5564 + i * 0.0005;
      Mechanic m = newMechanic(lat, 104.9282);
      m.setCurrentH3Index(h3.geoToH3Address(lat, 104.9282, 7));
      m = mechanicRepository.save(m);
      // FlowTestSupport stubs Medusa to resolve every service group to TOW.
      mechanicSkillRepository.save(
          MechanicSkill.builder().mechanic(m).skillTags(new String[] {"TOW"}).build());
    }
    Driver d = newDriver();
    Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
    stats.clear();

    createSosOk(d);

    long statements = stats.getPrepareStatementCount();
    // A fixed handful (projection lookups, radius pick, insert, fan-out, media, response); the
    // N+1 version issued several per matched mechanic, so 40 mechanics alone meant 160+.
    assertThat(statements)
        .as("SQL statements for one create reaching at least %d mechanics", MECHANICS)
        .isLessThan(30);
  }
}
