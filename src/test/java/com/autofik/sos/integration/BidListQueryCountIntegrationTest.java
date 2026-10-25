package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The customer's bid list must cost a fixed number of statements, however many mechanics bid. Each
 * bid carries its bidder with the rating, and loading the bids plainly cost one more SELECT per bid
 * to load its mechanic: twelve statements for ten bids, on the screen the customer refreshes during
 * the 60 s window. Boots its own context to turn Hibernate statistics on.
 */
class BidListQueryCountIntegrationTest extends FlowTestSupport {

  private static final int BIDDERS = 10;

  @DynamicPropertySource
  static void statistics(DynamicPropertyRegistry r) {
    r.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
  }

  @Autowired private EntityManagerFactory emf;

  @Test
  void theBidList_statementCount_doesNotGrowWithTheBids() throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);
    for (int i = 0; i < BIDDERS; i++) {
      placeBidOk(skilledMechanic(11.5564, 104.9282, "TOW"), sosId);
    }
    Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
    stats.clear();

    HttpResponse<String> r =
        send(
            req("/api/v1/bids/sos/" + sosId)
                .header("user-context", userContext(d.user().getUserId()))
                .GET());

    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(parse(r.body()).size()).isEqualTo(BIDDERS);
    // The ticket, then the bids with their mechanics and ratings in one join, plus a little
    // overhead. Plain loading was 1 + 1 + 1 per bid: 12 for ten.
    assertThat(stats.getPrepareStatementCount())
        .as("SQL statements for a bid list of %d", BIDDERS)
        .isLessThan(8);
  }
}
