package com.autofik.sos.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V3's indexes exist after the migrations run, and the sweep queries can use them. Sequential scans
 * are switched off for the check because on a near-empty table the planner rightly prefers one; the
 * question here is only whether the index matches the query.
 */
class HotPathIndexTest extends RepositoryTestBase {

  @Test
  void v3_createsEveryHotPathIndex() {
    @SuppressWarnings("unchecked")
    List<String> names =
        em.getEntityManager()
            .createNativeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
            .getResultList();

    assertThat(names)
        .contains(
            "idx_sos_request_status_expiry",
            "idx_sos_request_user_status",
            "idx_sos_status_history_sos_change",
            "idx_sos_status_history_pending",
            "idx_bid_sos_mechanic_status",
            "idx_mechanic_skill_mechanic",
            "idx_sos_media_sos");
  }

  @Test
  void theExpirySweep_canUseItsIndex() {
    assertThat(
            plan(
                "SELECT * FROM sos_request WHERE status = 'PENDING'"
                    + " AND bidding_expires_at < now()"))
        .contains("idx_sos_request_status_expiry");
  }

  @Test
  void theAutoApproveSweep_canUseThePartialIndex() {
    assertThat(
            plan(
                "SELECT * FROM sos_status_history WHERE approved_status = 'PENDING_APPROVAL'"
                    + " AND proposed_at < now()"))
        .contains("idx_sos_status_history_pending");
  }

  private String plan(String sql) {
    em.getEntityManager().createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();
    @SuppressWarnings("unchecked")
    List<String> rows = em.getEntityManager().createNativeQuery("EXPLAIN " + sql).getResultList();
    return String.join("\n", rows);
  }
}
