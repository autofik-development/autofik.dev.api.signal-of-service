package com.autofik.sos.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class BidSosRepositoryTest extends RepositoryTestBase {

  @Autowired BidSosRepository bidSosRepository;

  @Test
  void aMechanicCanHaveOnlyOneBidRowPerTicket() {
    SosRequest sos = sos(user(), SosStatus.PENDING, "TOW", LAT, LNG);
    Mechanic m = mechanic(LAT, LNG, null, "TOW");
    bid(sos, m, BidStatus.PENDING);

    // Through the repository, which translates the driver error the way the services see it.
    BidSos second =
        BidSos.builder().price(java.math.BigDecimal.ONE).sosRequest(sos).mechanic(m).build();
    assertThatThrownBy(() -> bidSosRepository.saveAndFlush(second))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void currentStatus_readsTheStoredStatus() {
    BidSos accepted =
        bid(
            sos(user(), SosStatus.ACCEPTED, "TOW", LAT, LNG),
            mechanic(LAT, LNG, null),
            BidStatus.ACCEPTED);

    assertThat(bidSosRepository.currentStatus(accepted.getBidId())).contains(BidStatus.ACCEPTED);
    assertThat(bidSosRepository.currentStatus(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findWithMechanicBySosIdsAndStatus_returnsOnlyThatStatusForTheGivenTickets() {
    SosRequest first = sos(user(), SosStatus.ACCEPTED, "TOW", LAT, LNG);
    SosRequest second = sos(user(), SosStatus.ACCEPTED, "TOW", LAT, LNG);
    SosRequest other = sos(user(), SosStatus.ACCEPTED, "TOW", LAT, LNG);
    Mechanic winner = mechanic(LAT, LNG, null);
    Mechanic loser = mechanic(LAT, LNG, null);
    bid(first, winner, BidStatus.ACCEPTED);
    bid(first, loser, BidStatus.REJECTED);
    bid(second, winner, BidStatus.ACCEPTED);
    bid(other, loser, BidStatus.ACCEPTED);
    em.clear();

    List<BidSos> found =
        bidSosRepository.findWithMechanicBySosIdsAndStatus(
            List.of(first.getSosId(), second.getSosId()), BidStatus.ACCEPTED);

    assertThat(found)
        .extracting(b -> b.getSosRequest().getSosId())
        .containsExactlyInAnyOrder(first.getSosId(), second.getSosId());
    assertThat(found)
        .allSatisfy(
            b -> assertThat(b.getMechanic().getMechanicId()).isEqualTo(winner.getMechanicId()));
  }

  @Test
  void findActiveJob_ignoresFinishedAndCancelledTickets() {
    Mechanic m = mechanic(LAT, LNG, null);
    bid(sos(user(), SosStatus.COMPLETED, "TOW", LAT, LNG), m, BidStatus.ACCEPTED);
    bid(sos(user(), SosStatus.CANCELLED, "TOW", LAT, LNG), m, BidStatus.ACCEPTED);
    assertThat(bidSosRepository.findActiveJob(m.getMechanicId())).isEmpty();

    SosRequest live = sos(user(), SosStatus.ON_THE_WAY, "TOW", LAT, LNG);
    bid(live, m, BidStatus.ACCEPTED);

    assertThat(bidSosRepository.findActiveJob(m.getMechanicId()))
        .hasValueSatisfying(
            b -> assertThat(b.getSosRequest().getSosId()).isEqualTo(live.getSosId()));
  }

  @Test
  void existsAssignment_needsAnAcceptedBidOnATicketInOneOfTheGivenStatuses() {
    SosRequest sos = sos(user(), SosStatus.ARRIVED, "TOW", LAT, LNG);
    Mechanic assigned = mechanic(LAT, LNG, null);
    Mechanic rejected = mechanic(LAT, LNG, null);
    bid(sos, assigned, BidStatus.ACCEPTED);
    bid(sos, rejected, BidStatus.REJECTED);
    List<SosStatus> live = BidSosRepository.ACTIVE_JOB_STATUSES;

    assertThat(
            bidSosRepository.existsAssignment(
                assigned.getMechanicId(), sos.getSosId(), BidStatus.ACCEPTED, live))
        .isTrue();
    assertThat(
            bidSosRepository.existsAssignment(
                rejected.getMechanicId(), sos.getSosId(), BidStatus.ACCEPTED, live))
        .isFalse();
    assertThat(
            bidSosRepository.existsAssignment(
                assigned.getMechanicId(),
                sos.getSosId(),
                BidStatus.ACCEPTED,
                List.of(SosStatus.COMPLETED)))
        .isFalse();
  }

  @Test
  void findAcceptedBeforeArrival_listsOnlyAcceptedAndOnTheWayJobs() {
    SosRequest accepted = sos(user(), SosStatus.ACCEPTED, "TOW", LAT, LNG);
    SosRequest onTheWay = sos(user(), SosStatus.ON_THE_WAY, "TOW", LAT, LNG);
    SosRequest arrived = sos(user(), SosStatus.ARRIVED, "TOW", LAT, LNG);
    bid(accepted, mechanic(LAT, LNG, null), BidStatus.ACCEPTED);
    bid(onTheWay, mechanic(LAT, LNG, null), BidStatus.ACCEPTED);
    bid(arrived, mechanic(LAT, LNG, null), BidStatus.ACCEPTED);

    assertThat(bidSosRepository.findAcceptedBeforeArrival())
        .extracting(b -> b.getSosRequest().getSosId())
        .contains(accepted.getSosId(), onTheWay.getSosId())
        .doesNotContain(arrived.getSosId());
  }
}
