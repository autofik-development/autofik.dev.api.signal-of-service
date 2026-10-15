package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Listing a driver's tickets must not look up the assigned mechanic (and its rating) once per row:
 * that cost 21+ round trips for a default page. Boots its own context to turn Hibernate statistics
 * on.
 */
class SosListQueryCountIntegrationTest extends FlowTestSupport {

  @DynamicPropertySource
  static void statistics(DynamicPropertyRegistry r) {
    r.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    // The test creates six tickets in a row for one driver.
    r.add("sos.create.cooldown-seconds", () -> "1");
  }

  @Autowired private EntityManagerFactory emf;

  @Test
  void listing_statementCount_doesNotGrowWithThePageSize() throws Exception {
    Driver d = newDriver();
    Mechanic m = skilledMechanic(11.5564, 104.9282, "TOW");
    for (int i = 0; i < 6; i++) {
      Thread.sleep(1100); // outlast the 1 s create cooldown
      UUID sosId = createSosOk(d);
      assignMechanic(d, m, sosId);
      HttpResponse<String> cancelled =
          send(
              req("/api/v1/sos/" + sosId + "/cancel")
                  .header("user-context", userContext(d.user().getUserId()))
                  .method("PATCH", HttpRequest.BodyPublishers.noBody()));
      assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
    }

    Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
    long small = statementsToList(d, stats, 2);
    long large = statementsToList(d, stats, 6);

    // One statement per extra row remains: SosRequest's inverse @OneToOne feedback loads eagerly
    // (rating/feedback mapping, deliberately not touched here). The assigned mechanic and its
    // rating used to add two more per row; they now come in one query for the page.
    assertThat(large - small)
        .as(
            "extra statements for 4 extra rows (page of 2 cost %d, page of 6 cost %d)",
            small, large)
        .isLessThanOrEqualTo(4);
  }

  private long statementsToList(Driver d, Statistics stats, int size) throws Exception {
    stats.clear();
    HttpResponse<String> r =
        send(
            req("/api/v1/sos?size=" + size)
                .header("user-context", userContext(d.user().getUserId()))
                .GET());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(parse(r.body()).get("content").size()).isEqualTo(size);
    // The rows carry the assigned mechanic, so the lookup under test really ran.
    assertThat(parse(r.body()).get("content").get(0).get("mechanic").isObject()).isTrue();
    return stats.getPrepareStatementCount();
  }
}
