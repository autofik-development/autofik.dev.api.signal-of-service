package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicRate;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.enums.MechanicRole;
import com.autofik.sos.repository.MechanicRateRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.MechanicSkillRepository;
import com.autofik.sos.security.MechanicContext;
import com.autofik.sos.service.impl.MechanicProfileSync;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The header is unsigned, so the sync's job is as much "refuse what is out of bounds" as "copy what
 * SSO says". Each bound and each thing it must not touch is pinned here.
 */
class MechanicProfileSyncTest {

  private final MechanicRepository mechanics = mock(MechanicRepository.class);
  private final MechanicSkillRepository skills = mock(MechanicSkillRepository.class);
  private final MechanicRateRepository rates = mock(MechanicRateRepository.class);

  // Runs the callback inline: the unit under test is the sync, not Spring's transaction manager.
  private final TransactionTemplate tx =
      new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          return action.doInTransaction(new SimpleTransactionStatus());
        }
      };

  private final MechanicProfileSync sync = new MechanicProfileSync(mechanics, skills, rates, tx);

  private final UUID id = UUID.randomUUID();

  private static MechanicContext.Mechanic ctx(
      String name,
      String phone,
      String email,
      String type,
      List<String> tags,
      Integer radius,
      String avgRate) {
    return new MechanicContext.Mechanic(
        "ignored",
        "MECHANIC",
        null,
        true,
        true,
        "active",
        false,
        phone,
        type,
        new MechanicContext.Profile(name, email, null),
        new MechanicContext.SkillService(tags, radius),
        avgRate);
  }

  private MechanicContext.Mechanic sample() {
    return ctx(
        "John Doe",
        "+855123456789",
        "john@example.com",
        "freelance",
        List.of("engine_repair", "brake_service", "engine_repair"),
        50,
        "4.80");
  }

  private void stubNoRateRow() {
    when(rates.findByMechanic(any())).thenReturn(Optional.empty());
  }

  @Test
  void aNewMechanic_storesItsProfileImage_onlyWhenHttps() {
    when(mechanics.findById(id)).thenReturn(Optional.empty());
    when(mechanics.save(any(Mechanic.class))).thenAnswer(i -> i.getArgument(0));
    stubNoRateRow();
    MechanicContext.Mechanic withImage =
        new MechanicContext.Mechanic(
            "x",
            "MECHANIC",
            null,
            true,
            true,
            "active",
            false,
            "+855123456789",
            "freelance",
            new MechanicContext.Profile("John Doe", null, "https://cdn.example/m/1.png"),
            null,
            null);

    sync.sync(id, withImage);

    ArgumentCaptor<Mechanic> saved = ArgumentCaptor.forClass(Mechanic.class);
    verify(mechanics).save(saved.capture());
    assertThat(saved.getValue().getProfileImage()).isEqualTo("https://cdn.example/m/1.png");
  }

  @Test
  void aHeaderWithNoProfile_changesNothingAndTouchesNoRepository() {
    MechanicContext.Mechanic plain =
        new MechanicContext.Mechanic(
            "x", "MECHANIC", null, true, true, "active", false, null, null, null, null, null);

    sync.sync(id, plain);
    sync.sync(id, null);

    verifyNoInteractions(mechanics, skills, rates);
  }

  @Test
  void aMechanicSeenForTheFirstTime_isCreatedFromTheProfile() {
    when(mechanics.findById(id)).thenReturn(Optional.empty());
    when(mechanics.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of());
    stubNoRateRow();

    sync.sync(id, sample());

    ArgumentCaptor<Mechanic> created = ArgumentCaptor.forClass(Mechanic.class);
    verify(mechanics).save(created.capture());
    assertThat(created.getValue().getMechanicId()).isEqualTo(id);
    assertThat(created.getValue().getFullName()).isEqualTo("John Doe");
    assertThat(created.getValue().getPhoneNumber()).isEqualTo("+855123456789");
    assertThat(created.getValue().getEmail()).isEqualTo("john@example.com");
    assertThat(created.getValue().getMechanicRole()).isEqualTo(MechanicRole.FREELANCE);
    assertThat(created.getValue().getServiceRadiusKm()).isEqualTo(50);

    ArgumentCaptor<MechanicSkill> skill = ArgumentCaptor.forClass(MechanicSkill.class);
    verify(skills).save(skill.capture());
    assertThat(skill.getValue().getSkillTags()).containsExactly("ENGINE_REPAIR", "BRAKE_SERVICE");

    ArgumentCaptor<MechanicRate> rate = ArgumentCaptor.forClass(MechanicRate.class);
    verify(rates).save(rate.capture());
    assertThat(rate.getValue().getRatingAvg()).isEqualByComparingTo("4.80");
    assertThat(rate.getValue().getRatingCount()).as("the count is not in the header").isZero();
  }

  @Test
  void aKnownMechanic_isUpdated_butNeverItsOwnState() {
    Point where = new GeometryFactory().createPoint(new Coordinate(104.9, 11.5));
    Mechanic existing =
        Mechanic.builder()
            .mechanicId(id)
            .fullName("Old Name")
            .phoneNumber("+85500000000")
            .serviceRadiusKm(10)
            .availableStatus(false)
            .currentLocation(where)
            .currentH3Index("8730e0a85ffffff")
            .build();
    MechanicSkill first =
        MechanicSkill.builder().mechanic(existing).skillTags(new String[] {"TOW"}).build();
    MechanicSkill extra =
        MechanicSkill.builder().mechanic(existing).skillTags(new String[] {"X"}).build();
    when(mechanics.findById(id)).thenReturn(Optional.of(existing));
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of(first, extra));
    MechanicRate rate =
        MechanicRate.builder()
            .mechanic(existing)
            .ratingAvg(new BigDecimal("3.00"))
            .ratingCount(7)
            .build();
    when(rates.findByMechanic(existing)).thenReturn(Optional.of(rate));

    sync.sync(id, sample());

    assertThat(existing.getFullName()).isEqualTo("John Doe");
    assertThat(existing.getServiceRadiusKm()).isEqualTo(50);
    assertThat(existing.getAvailableStatus()).as("availability is SOS's own").isFalse();
    assertThat(existing.getCurrentLocation()).isSameAs(where);
    assertThat(existing.getCurrentH3Index()).isEqualTo("8730e0a85ffffff");
    assertThat(first.getSkillTags()).containsExactly("ENGINE_REPAIR", "BRAKE_SERVICE");
    verify(skills).deleteAll(List.of(extra));
    assertThat(rate.getRatingAvg()).isEqualByComparingTo("4.80");
    assertThat(rate.getRatingCount()).as("the count is left alone").isEqualTo(7);
  }

  @Test
  void aProfileThatHasNotChanged_costsNoQuery() {
    when(mechanics.findById(id)).thenReturn(Optional.empty());
    when(mechanics.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of());
    stubNoRateRow();

    sync.sync(id, sample());
    sync.sync(id, sample());

    verify(mechanics, times(1)).findById(id);
  }

  @Test
  void aChangedProfile_isWrittenAgain() {
    when(mechanics.findById(id)).thenReturn(Optional.empty());
    when(mechanics.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of());
    stubNoRateRow();

    sync.sync(id, sample());
    sync.sync(
        id,
        ctx(
            "John Doe",
            "+855123456789",
            "john@example.com",
            "freelance",
            List.of("tow"),
            50,
            "4.80"));

    verify(mechanics, times(2)).findById(id);
  }

  @Test
  void outOfBoundsValues_areSkipped_andTheRestStillSyncs() {
    Mechanic existing =
        Mechanic.builder()
            .mechanicId(id)
            .fullName("Old Name")
            .phoneNumber("+85500000000")
            .serviceRadiusKm(10)
            .build();
    when(mechanics.findById(id)).thenReturn(Optional.of(existing));
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of());
    MechanicContext.Mechanic hostile =
        ctx(
            "a name that is much longer than thirty characters",
            "+855123456789",
            null,
            "spaceship",
            List.of("tow"),
            5000,
            "7.00");

    sync.sync(id, hostile);

    assertThat(existing.getFullName()).as("too long, kept").isEqualTo("Old Name");
    assertThat(existing.getServiceRadiusKm()).as("5000 km, kept").isEqualTo(10);
    assertThat(existing.getMechanicRole()).as("unknown type, kept").isNull();
    assertThat(existing.getPhoneNumber())
        .as("the valid part still syncs")
        .isEqualTo("+855123456789");
    verify(rates, never()).save(any());
  }

  @Test
  void aRatingThatIsNotANumber_isIgnored() {
    Mechanic existing = Mechanic.builder().mechanicId(id).fullName("N").phoneNumber("1").build();
    when(mechanics.findById(id)).thenReturn(Optional.of(existing));
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of());

    sync.sync(id, ctx("N", "1", null, null, List.of("tow"), 10, "abc"));

    verify(rates, never()).save(any());
  }

  @Test
  void anEmptyTagList_keepsTheOldTags() {
    Mechanic existing = Mechanic.builder().mechanicId(id).fullName("N").phoneNumber("1").build();
    when(mechanics.findById(id)).thenReturn(Optional.of(existing));
    stubNoRateRow();

    sync.sync(id, ctx("N", "1", null, null, List.of(), 10, "4.00"));

    verify(skills, never()).save(any());
    verify(skills, never()).deleteAll(any());
  }

  @Test
  void aNewMechanic_withNoUsableNameOrPhone_isNotCreated() {
    when(mechanics.findById(id)).thenReturn(Optional.empty());

    sync.sync(id, ctx(null, "+855123456789", null, "freelance", List.of("tow"), 10, "4.00"));

    verify(mechanics, never()).save(any());
    verify(skills, never()).save(any());
  }

  @Test
  void twoFirstRequestsRacing_theLoserUpdatesInsteadOfFailing() {
    Mechanic winner = Mechanic.builder().mechanicId(id).fullName("N").phoneNumber("1").build();
    when(mechanics.findById(id)).thenReturn(Optional.empty(), Optional.of(winner));
    doThrow(new DataIntegrityViolationException("duplicate key"))
        .doAnswer(inv -> inv.getArgument(0))
        .when(mechanics)
        .save(any(Mechanic.class));
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of());
    stubNoRateRow();

    assertThatCode(() -> sync.sync(id, sample())).doesNotThrowAnyException();

    assertThat(winner.getFullName()).isEqualTo("John Doe");
  }

  @Test
  void aFailure_neverReachesTheRequest_andIsRetriedNextTime() {
    when(mechanics.findById(id)).thenThrow(new IllegalStateException("db down"));

    assertThatCode(() -> sync.sync(id, sample())).doesNotThrowAnyException();
    assertThatCode(() -> sync.sync(id, sample())).doesNotThrowAnyException();

    verify(mechanics, times(2)).findById(id);
  }
}
