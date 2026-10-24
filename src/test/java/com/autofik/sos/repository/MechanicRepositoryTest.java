package com.autofik.sos.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

  // ── isEligible: one mechanic, same answer as the area matcher ────────────

  /**
   * What isEligibleMechanic did before: the area matcher, then the radius filter, then contains.
   */
  private boolean viaTheAreaMatcher(Mechanic m, List<String> cells, Double broadcastKm) {
    LocalDateTime seen = LocalDateTime.now().minusMinutes(5);
    List<UUID> coarse = mechanicRepository.findByH3CellsAndSkillTag(cells, "TOW", seen);
    if (!coarse.contains(m.getMechanicId())) return false;
    List<UUID> matched =
        broadcastKm != null
            ? mechanicRepository.filterByRadiusAndBroadcast(coarse, LAT, LNG, broadcastKm * 1000)
            : mechanicRepository.filterByRadius(coarse, LAT, LNG);
    return matched.contains(m.getMechanicId());
  }

  private boolean viaTheSingleQuery(Mechanic m, List<String> cells, Double broadcastKm) {
    double radiusM = broadcastKm != null ? broadcastKm * 1000 : 1e12;
    return mechanicRepository.isEligible(
        m.getMechanicId(), cells, "TOW", LocalDateTime.now().minusMinutes(5), LAT, LNG, radiusM);
  }

  @Test
  void isEligible_agreesWithTheAreaMatcher_forEveryPredicate() {
    Mechanic eligible = mechanic(LAT, LNG, CELL, "tow");
    Mechanic wrongCell = mechanic(LAT, LNG, OTHER_CELL, "TOW");
    Mechanic wrongSkill = mechanic(LAT, LNG, CELL, "BATTERY");
    Mechanic unavailable = mechanic(LAT, LNG, CELL, "TOW");
    unavailable.setAvailableStatus(false);
    Mechanic stale = mechanic(LAT, LNG, CELL, "TOW");
    stale.setLocationUpdatedAt(LocalDateTime.now().minusHours(1));
    Mechanic neverPinged = mechanic(LAT, LNG, CELL, "TOW"); // no ping time on record: kept
    Mechanic fresh = mechanic(LAT, LNG, CELL, "TOW");
    fresh.setLocationUpdatedAt(LocalDateTime.now());
    Mechanic onLiveJob = mechanic(LAT, LNG, CELL, "TOW");
    bid(sos(user(), SosStatus.ON_THE_WAY, "TOW", LAT, LNG), onLiveJob, BidStatus.ACCEPTED);
    Mechanic jobDone = mechanic(LAT, LNG, CELL, "TOW");
    bid(sos(user(), SosStatus.COMPLETED, "TOW", LAT, LNG), jobDone, BidStatus.ACCEPTED);
    Mechanic outsideOwnRadius = mechanic(LAT + 0.5, LNG, CELL, "TOW"); // ~55 km, own radius 10 km
    Mechanic wideRadiusFar = mechanic(LAT + 0.5, LNG, CELL, "TOW");
    wideRadiusFar.setServiceRadiusKm(100);
    Mechanic inCellOutsideBroadcast = mechanic(LAT + 0.05, LNG, CELL, "TOW"); // ~5.5 km
    Mechanic noLocation = mechanic(LAT, LNG, CELL, "TOW");
    noLocation.setCurrentLocation(null);
    em.flush();

    List<Mechanic> all =
        List.of(
            eligible,
            wrongCell,
            wrongSkill,
            unavailable,
            stale,
            neverPinged,
            fresh,
            onLiveJob,
            jobDone,
            outsideOwnRadius,
            wideRadiusFar,
            inCellOutsideBroadcast,
            noLocation);
    for (Double broadcastKm : new Double[] {10.0, 3.0, null}) {
      for (Mechanic m : all) {
        assertThat(viaTheSingleQuery(m, List.of(CELL), broadcastKm))
            .as("mechanic %s, broadcast %s km", m.getFullName(), broadcastKm)
            .isEqualTo(viaTheAreaMatcher(m, List.of(CELL), broadcastKm));
      }
    }

    // The answers are not all the same, so the comparison means something.
    assertThat(viaTheSingleQuery(eligible, List.of(CELL), 10.0)).isTrue();
    assertThat(viaTheSingleQuery(onLiveJob, List.of(CELL), 10.0)).isFalse();
    assertThat(viaTheSingleQuery(jobDone, List.of(CELL), 10.0)).isTrue();
    assertThat(viaTheSingleQuery(neverPinged, List.of(CELL), 10.0)).isTrue();
    assertThat(viaTheSingleQuery(stale, List.of(CELL), 10.0)).isFalse();
    assertThat(viaTheSingleQuery(inCellOutsideBroadcast, List.of(CELL), 3.0)).isFalse();
    assertThat(viaTheSingleQuery(inCellOutsideBroadcast, List.of(CELL), 10.0)).isTrue();
    // No broadcast radius: only the mechanic's own radius limits them.
    assertThat(viaTheSingleQuery(wideRadiusFar, List.of(CELL), null)).isTrue();
    assertThat(viaTheSingleQuery(outsideOwnRadius, List.of(CELL), null)).isFalse();
  }

  // The ring computed from the default radius still limits a mechanic with a huge own radius, as in
  // the area matcher: the cell list is part of the question.
  @Test
  void isEligible_aCellOutsideTheRing_isNotEligible_whateverTheRadius() {
    Mechanic m = mechanic(LAT, LNG, OTHER_CELL, "TOW");
    m.setServiceRadiusKm(100);
    em.flush();

    assertThat(viaTheSingleQuery(m, List.of(CELL), null)).isFalse();
    assertThat(viaTheSingleQuery(m, List.of(CELL, OTHER_CELL), null)).isTrue();
    assertThat(viaTheAreaMatcher(m, List.of(CELL), null)).isFalse();
  }

  @Test
  void isEligible_anUnknownMechanic_isFalse() {
    assertThat(
            mechanicRepository.isEligible(
                UUID.randomUUID(),
                List.of(CELL),
                "TOW",
                LocalDateTime.now().minusMinutes(5),
                LAT,
                LNG,
                10_000))
        .isFalse();
  }

  @Test
  void lockById_findsAnExistingMechanicOnly() {
    Mechanic m = mechanic(LAT, LNG, null);

    assertThat(mechanicRepository.lockById(m.getMechanicId())).contains(m.getMechanicId());
    assertThat(mechanicRepository.lockById(UUID.randomUUID())).isEmpty();
  }

  // ── updateLocation: one statement per persisted ping ─────────────────────

  private static final LocalDateTime LONG_AGO = LocalDateTime.of(2026, 1, 1, 0, 0);

  /** The statement bypasses the persistence context, so read the row afresh. */
  private Mechanic reload(Mechanic m) {
    em.flush();
    em.clear();
    return em.find(Mechanic.class, m.getMechanicId());
  }

  private Mechanic parkedSince(LocalDateTime movedAt) {
    Mechanic m = mechanic(LAT, LNG, null);
    m.setLocationMovedAt(movedAt);
    em.flush();
    return m;
  }

  @Test
  void updateLocation_setsPositionCellAndTimestamp() {
    Mechanic m = parkedSince(LONG_AGO);
    LocalDateTime now = LocalDateTime.now();

    int rows =
        mechanicRepository.updateLocation(m.getMechanicId(), LAT + 0.01, LNG + 0.02, CELL, 20, now);
    Mechanic after = reload(m);

    assertThat(rows).isEqualTo(1);
    assertThat(after.getCurrentLocation().getY()).isCloseTo(LAT + 0.01, within(1e-9));
    assertThat(after.getCurrentLocation().getX()).isCloseTo(LNG + 0.02, within(1e-9));
    assertThat(after.getCurrentH3Index()).isEqualTo(CELL);
    assertThat(after.getLocationUpdatedAt()).isCloseTo(now, within(1, ChronoUnit.MILLIS));
  }

  // The no-show sweep tells a parked mechanic from a moving one by location_moved_at, which
  // location_updated_at cannot do: it is stamped on every ping.
  @Test
  void updateLocation_aFarEnoughPing_recordsThatTheMechanicMoved() {
    Mechanic m = parkedSince(LONG_AGO);
    LocalDateTime now = LocalDateTime.now();

    // About 28 m north.
    mechanicRepository.updateLocation(m.getMechanicId(), LAT + 0.00025, LNG, CELL, 20, now);

    assertThat(reload(m).getLocationMovedAt()).isCloseTo(now, within(1, ChronoUnit.MILLIS));
  }

  @Test
  void updateLocation_aPingNearThePreviousOne_doesNotRecordMovement() {
    Mechanic m = parkedSince(LONG_AGO);

    // About 5 m north: GPS wobble, not movement.
    mechanicRepository.updateLocation(
        m.getMechanicId(), LAT + 0.000045, LNG, CELL, 20, LocalDateTime.now());
    Mechanic after = reload(m);

    assertThat(after.getLocationMovedAt()).isEqualTo(LONG_AGO);
    assertThat(after.getLocationUpdatedAt()).isAfter(LONG_AGO); // still stamped as seen
  }

  @Test
  void updateLocation_theFirstEverLocation_countsAsMoved() {
    Mechanic m = parkedSince(LONG_AGO);
    m.setCurrentLocation(null);
    em.flush();
    LocalDateTime now = LocalDateTime.now();

    mechanicRepository.updateLocation(m.getMechanicId(), LAT, LNG, CELL, 20, now);

    assertThat(reload(m).getLocationMovedAt()).isCloseTo(now, within(1, ChronoUnit.MILLIS));
  }

  @Test
  void updateLocation_anUnknownMechanic_changesNothing() {
    assertThat(
            mechanicRepository.updateLocation(
                UUID.randomUUID(), LAT, LNG, CELL, 20, LocalDateTime.now()))
        .isZero();
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
