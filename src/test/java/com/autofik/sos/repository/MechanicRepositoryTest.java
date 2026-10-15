package com.autofik.sos.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MechanicRepositoryTest extends RepositoryTestBase {

  private static final String CELL = "8730f3b04ffffff";
  private static final String OTHER_CELL = "8730f3b05ffffff";

  @Autowired MechanicRepository mechanicRepository;

  private static List<UUID> ids(Mechanic... mechanics) {
    return Arrays.stream(mechanics).map(Mechanic::getMechanicId).toList();
  }

  @Test
  void filterByRadius_usesTheMechanicsOwnServiceRadius() {
    Mechanic near = mechanic(LAT + 0.02, LNG, null); // ~2.2 km, radius 10 km
    Mechanic far = mechanic(LAT + 0.5, LNG, null); // ~55 km
    Mechanic wideRadius = mechanic(LAT + 0.5, LNG, null);
    wideRadius.setServiceRadiusKm(100);
    em.flush();

    assertThat(mechanicRepository.filterByRadius(ids(near, far, wideRadius), LAT, LNG))
        .containsExactlyInAnyOrder(near.getMechanicId(), wideRadius.getMechanicId());
  }

  @Test
  void filterByRadiusAndBroadcast_appliesTheSmallerOfTheTwoRadii() {
    Mechanic m = mechanic(LAT + 0.05, LNG, null); // ~5.5 km away, own radius 10 km

    assertThat(mechanicRepository.filterByRadiusAndBroadcast(ids(m), LAT, LNG, 8_000)).hasSize(1);
    assertThat(mechanicRepository.filterByRadiusAndBroadcast(ids(m), LAT, LNG, 3_000)).isEmpty();
  }

  @Test
  void findByH3CellsAndSkillTag_matchesCellSkillAvailabilityAndFreshness() {
    LocalDateTime seenSince = LocalDateTime.now().minusMinutes(5);
    Mechanic match = mechanic(LAT, LNG, CELL, "tow", "BATTERY"); // tags compare upper-cased
    Mechanic wrongCell = mechanic(LAT, LNG, OTHER_CELL, "TOW");
    Mechanic wrongSkill = mechanic(LAT, LNG, CELL, "BATTERY");
    Mechanic unavailable = mechanic(LAT, LNG, CELL, "TOW");
    unavailable.setAvailableStatus(false);
    Mechanic stale = mechanic(LAT, LNG, CELL, "TOW");
    stale.setLocationUpdatedAt(LocalDateTime.now().minusHours(1));
    Mechanic neverPinged = mechanic(LAT, LNG, CELL, "TOW"); // no ping time on record: kept
    em.flush();

    assertThat(mechanicRepository.findByH3CellsAndSkillTag(List.of(CELL), "TOW", seenSince))
        .containsExactlyInAnyOrder(match.getMechanicId(), neverPinged.getMechanicId())
        .doesNotContain(
            wrongCell.getMechanicId(),
            wrongSkill.getMechanicId(),
            unavailable.getMechanicId(),
            stale.getMechanicId());
  }

  @Test
  void findByH3CellsAndSkillTag_dropsAMechanicOnALiveJob() {
    Mechanic busy = mechanic(LAT, LNG, CELL, "TOW");
    Mechanic finished = mechanic(LAT, LNG, CELL, "TOW");
    SosRequest live = sos(user(), SosStatus.ON_THE_WAY, "TOW", LAT, LNG);
    SosRequest done = sos(user(), SosStatus.COMPLETED, "TOW", LAT, LNG);
    bid(live, busy, BidStatus.ACCEPTED);
    bid(done, finished, BidStatus.ACCEPTED);

    assertThat(
            mechanicRepository.findByH3CellsAndSkillTag(
                List.of(CELL), "TOW", LocalDateTime.now().minusMinutes(5)))
        .containsExactly(finished.getMechanicId());
  }

  @Test
  void lockById_findsAnExistingMechanicOnly() {
    Mechanic m = mechanic(LAT, LNG, null);

    assertThat(mechanicRepository.lockById(m.getMechanicId())).contains(m.getMechanicId());
    assertThat(mechanicRepository.lockById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findByIdsWithSkills_returnsOnlyAvailableMechanics_withSkillsLoaded() {
    Mechanic available = mechanic(LAT, LNG, null, "TOW");
    Mechanic unavailable = mechanic(LAT, LNG, null, "TOW");
    unavailable.setAvailableStatus(false);
    em.flush();
    em.clear();

    List<Mechanic> found = mechanicRepository.findByIdsWithSkills(ids(available, unavailable));

    assertThat(found)
        .extracting(Mechanic::getMechanicId)
        .containsExactly(available.getMechanicId());
    assertThat(found.get(0).getSkills()).hasSize(1); // loaded by the join, not lazily
  }
}
