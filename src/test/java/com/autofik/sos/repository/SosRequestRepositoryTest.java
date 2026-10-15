package com.autofik.sos.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SosRequestRepositoryTest extends RepositoryTestBase {

  @Autowired SosRequestRepository sosRequestRepository;

  @Test
  void lockStatus_returnsTheStoredStatus_andEmptyForAnUnknownTicket() {
    SosRequest sos = sos(user(), SosStatus.ARRIVED, "TOW", LAT, LNG);

    assertThat(sosRequestRepository.lockStatus(sos.getSosId())).contains("ARRIVED");
    assertThat(sosRequestRepository.lockStatus(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findOpenIdsNear_returnsOnlyOpenNearbyTicketsOfASkillTheMechanicHas() {
    Mechanic m = mechanic(LAT, LNG, null, "TOW");
    SosRequest open = sos(user(), SosStatus.PENDING, "TOW", LAT + 0.01, LNG);
    SosRequest wrongSkill = sos(user(), SosStatus.PENDING, "BATTERY", LAT, LNG);
    SosRequest tooFar = sos(user(), SosStatus.PENDING, "TOW", LAT + 1.0, LNG); // ~111 km
    SosRequest notPending = sos(user(), SosStatus.ACCEPTED, "TOW", LAT, LNG);
    SosRequest windowClosed = sos(user(), SosStatus.PENDING, "TOW", LAT, LNG);
    windowClosed.setBiddingExpiresAt(LocalDateTime.now().minusMinutes(1));
    SosRequest alreadyBid = sos(user(), SosStatus.PENDING, "TOW", LAT, LNG);
    bid(alreadyBid, m, BidStatus.PENDING);
    em.flush();

    assertThat(sosRequestRepository.findOpenIdsNear(m.getMechanicId(), LocalDateTime.now(), 10))
        .containsExactly(open.getSosId())
        .doesNotContain(
            wrongSkill.getSosId(),
            tooFar.getSosId(),
            notPending.getSosId(),
            windowClosed.getSosId(),
            alreadyBid.getSosId());
  }

  @Test
  void findOpenIdsNear_isNewestFirst_andHonoursTheLimit() throws Exception {
    Mechanic m = mechanic(LAT, LNG, null, "TOW");
    SosRequest older = sos(user(), SosStatus.PENDING, "TOW", LAT, LNG);
    Thread.sleep(20); // createdAt has millisecond resolution
    SosRequest newer = sos(user(), SosStatus.PENDING, "TOW", LAT, LNG);

    assertThat(sosRequestRepository.findOpenIdsNear(m.getMechanicId(), LocalDateTime.now(), 10))
        .containsExactly(newer.getSosId(), older.getSosId());
    assertThat(sosRequestRepository.findOpenIdsNear(m.getMechanicId(), LocalDateTime.now(), 1))
        .containsExactly(newer.getSosId());
  }
}
