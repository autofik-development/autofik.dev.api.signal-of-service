package com.autofik.sos.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * F-07's two backstops exist after the migrations run, and they actually catch the violation the
 * row-lock discipline is meant to prevent. The services enforce both invariants under the ticket
 * lock; these indexes are for the code path (or the manual SQL) that skips it. Flushing through the
 * repository translates the PostgreSQL unique violation to {@link DataIntegrityViolationException},
 * which {@code GlobalExceptionHandler} already answers with 409.
 *
 * <p>The violation tests deliberately corrupt history (flip a bid to ACCEPTED without the lock, or
 * stack a second live ticket) because that is exactly what a broken path would do. Every test
 * flushes the violation as its last step: once Postgres rejects, the transaction is done.
 */
class InvariantIndexTest extends RepositoryTestBase {

  @Test
  void v5_createsBothInvariantIndexes() {
    @SuppressWarnings("unchecked")
    List<String> names =
        em.getEntityManager()
            .createNativeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
            .getResultList();

    assertThat(names).contains("uq_sos_one_active_per_user", "uq_bid_one_accepted_per_sos");
  }

  @Test
  void aSecondTicketInAnyActiveStatus_forTheSameUser_isRefusedByPostgres() {
    User owner = user();
    // One live job in a non-PENDING state: the create-path check guards PENDING, but a manual
    // UPDATE (or a forgotten transition) could leave two active rows behind, PENDING first.
    sos(owner, SosStatus.ACCEPTED, "TOW", LAT, LNG);

    SosRequest second =
        SosRequest.builder()
            .problemType("BATTERY")
            .status(SosStatus.IN_PROGRESS)
            .location(point(LAT, LNG))
            .biddingExpiresAt(LocalDateTime.now().plusMinutes(10))
            .user(owner)
            .vehicle(vehicle(owner))
            .build();

    assertThatThrownBy(
            () -> {
              em.persist(second);
              em.flush();
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void closedStatuses_coexistOnOneUserWithoutViolation() {
    User owner = user();
    sos(owner, SosStatus.COMPLETED, "TOW", LAT, LNG);
    sos(owner, SosStatus.CANCELLED, "BATTERY", LAT, LNG);
    sos(owner, SosStatus.EXPIRED, "TOW", LAT, LNG);
    // Three closed stories for one user is normal history: they flush clean inside the helpers.
  }

  @Test
  void aSecondAcceptedBid_forTheSameTicket_isRefusedByPostgres() {
    SosRequest ticket = sos(user(), SosStatus.PENDING, "TOW", LAT, LNG);
    bid(ticket, mechanic(LAT, LNG, null), BidStatus.ACCEPTED);
    // A PENDING bid becoming ACCEPTED bypasses the accept path's re-read: this is what the
    // backstop exists for. Each mechanic is unique, so uk_bid_sos_sos_mechanic (V2) is not the
    // constraint doing the work here.
    var sneaky = bid(ticket, mechanic(LAT, LNG, null), BidStatus.PENDING);
    sneaky.setStatus(BidStatus.ACCEPTED);
    assertThatThrownBy(() -> em.flush()).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void rejectedAndPendingBids_coexistWithTheAcceptedOne() {
    SosRequest ticket = sos(user(), SosStatus.PENDING, "TOW", LAT, LNG);
    bid(ticket, mechanic(LAT, LNG, null), BidStatus.ACCEPTED);
    bid(ticket, mechanic(LAT, LNG, null), BidStatus.REJECTED);
    bid(ticket, mechanic(LAT, LNG, null), BidStatus.PENDING);
    // The domino's end state: one ACCEPTED, others REJECTED/PENDING, on the same ticket rows.
  }
}
