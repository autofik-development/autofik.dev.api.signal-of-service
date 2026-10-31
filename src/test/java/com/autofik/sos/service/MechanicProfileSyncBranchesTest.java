package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** The edges of the sync: every bound, every "header said nothing", every retry. */
class MechanicProfileSyncBranchesTest {

  private final MechanicRepository mechanics = mock(MechanicRepository.class);
  private final MechanicSkillRepository skills = mock(MechanicSkillRepository.class);
  private final MechanicRateRepository rates = mock(MechanicRateRepository.class);

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
      MechanicContext.Profile profile,
      MechanicContext.SkillService skill,
      String phone,
      String type,
      String avg) {
    return new MechanicContext.Mechanic(
        "x", "MECHANIC", null, true, true, "active", false, phone, type, profile, skill, avg);
  }

  private static MechanicContext.Profile profile(String name, String email) {
    return new MechanicContext.Profile(name, email, null);
  }

  private static MechanicContext.SkillService skill(List<String> tags, Integer radius) {
    return new MechanicContext.SkillService(tags, radius);
  }

  private Mechanic existing() {
    Mechanic m = Mechanic.builder().mechanicId(id).fullName("Old").phoneNumber("0").build();
    when(mechanics.findById(id)).thenReturn(Optional.of(m));
    when(mechanics.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));
    return m;
  }

  private void noMechanic() {
    when(mechanics.findById(id)).thenReturn(Optional.empty());
    when(mechanics.save(any(Mechanic.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  // ── guards ──

  @Test
  void nullMechanicId_isIgnored() {
    sync.sync(null, ctx(profile("A", null), null, "1", "gar", null));
    verify(mechanics, never()).findById(any());
  }

  @Test
  void profileOnly_withoutSkillService_stillSyncs() {
    Mechanic m = existing();
    when(rates.findByMechanic(any())).thenReturn(Optional.empty());

    sync.sync(id, ctx(profile("New Name", "n@x.io"), null, "081", "under garage", null));

    assertThat(m.getFullName()).isEqualTo("New Name");
    assertThat(m.getEmail()).isEqualTo("n@x.io");
    assertThat(m.getMechanicRole()).isEqualTo(MechanicRole.UNDER_GARAGE);
  }

  @Test
  void skillServiceOnly_withoutProfile_stillSyncs() {
    Mechanic m = existing();

    sync.sync(id, ctx(null, skill(null, 20), null, null, null));

    assertThat(m.getServiceRadiusKm()).isEqualTo(20);
    assertThat(m.getFullName()).isEqualTo("Old");
    verify(skills, never()).findByMechanicMechanicId(any());
  }

  // ── bounds ──

  @Test
  void outOfBoundValues_areNotStored() {
    Mechanic m = existing();
    String tooLong = "x".repeat(40);

    sync.sync(
        id, ctx(profile(tooLong, "   "), skill(List.of(), 500), "9".repeat(30), "mystery", "7.5"));

    assertThat(m.getFullName()).isEqualTo("Old");
    assertThat(m.getEmail()).isNull();
    assertThat(m.getPhoneNumber()).isEqualTo("0");
    assertThat(m.getMechanicRole()).isNull();
    assertThat(m.getServiceRadiusKm()).isNotEqualTo(500);
    verify(rates, never()).save(any());
    verify(skills, never()).findByMechanicMechanicId(any());
  }

  @Test
  void radiusBelowOne_negativeAndGarbageRating_andBlankRole_areSkipped() {
    existing();

    sync.sync(id, ctx(profile("A", null), skill(null, 0), "1", "  ", "-1"));
    sync.sync(id, ctx(profile("B", null), skill(null, 1), "1", "freeland", "abc"));
    sync.sync(id, ctx(profile("C", null), skill(null, 1), "1", "garage", "  "));

    verify(rates, never()).save(any());
  }

  @Test
  void tooManyAndOddTags_areTrimmedAndCapped() {
    existing();
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of());
    List<String> tags = new ArrayList<>();
    tags.add(null);
    tags.add("  ");
    tags.add("t".repeat(60));
    for (int i = 0; i < 40; i++) tags.add("tag" + i);

    sync.sync(id, ctx(profile("A", null), skill(tags, null), "1", null, null));

    ArgumentCaptor<MechanicSkill> saved = ArgumentCaptor.forClass(MechanicSkill.class);
    verify(skills).save(saved.capture());
    assertThat(saved.getValue().getSkillTags()).hasSize(30);
  }

  // ── creating ──

  @Test
  void unknownMechanic_withoutNameOrPhone_isNotCreated() {
    noMechanic();

    sync.sync(id, ctx(profile(null, null), skill(null, null), "1", null, null));
    sync.sync(id, ctx(profile("Name", null), skill(null, null), null, null, null));

    verify(mechanics, never()).save(any());
  }

  @Test
  void newMechanic_withoutRadius_getsTenKm() {
    noMechanic();
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of());

    sync.sync(id, ctx(profile("Name", null), skill(List.of("a"), null), "081", null, null));

    ArgumentCaptor<Mechanic> saved = ArgumentCaptor.forClass(Mechanic.class);
    verify(mechanics).save(saved.capture());
    assertThat(saved.getValue().getServiceRadiusKm()).isEqualTo(10);
  }

  // ── skills ──

  @Test
  void sameTags_areNotRewritten_andExtraRowsAreDeleted() {
    existing();
    MechanicSkill first = MechanicSkill.builder().skillTags(new String[] {"A"}).build();
    MechanicSkill extra = MechanicSkill.builder().skillTags(new String[] {"B"}).build();
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of(first, extra));

    sync.sync(id, ctx(profile("A", null), skill(List.of("a"), null), "1", null, null));

    verify(skills, never()).save(any());
    verify(skills).deleteAll(List.of(extra));
  }

  @Test
  void differentTags_overwriteTheFirstRow() {
    existing();
    MechanicSkill first = MechanicSkill.builder().skillTags(new String[] {"A"}).build();
    when(skills.findByMechanicMechanicId(id)).thenReturn(List.of(first));

    sync.sync(id, ctx(profile("A", null), skill(List.of("z"), null), "1", null, null));

    assertThat(first.getSkillTags()).containsExactly("Z");
    verify(skills).save(first);
    verify(skills, never()).deleteAll(any());
  }

  // ── rating ──

  @Test
  void rating_unchanged_isNotSaved_changed_is() {
    Mechanic m = existing();
    MechanicRate same =
        MechanicRate.builder().mechanic(m).ratingAvg(new BigDecimal("4.50")).build();
    when(rates.findByMechanic(any())).thenReturn(Optional.of(same));

    sync.sync(id, ctx(profile("A", null), null, "1", null, "4.5"));
    verify(rates, never()).save(any());

    sync.sync(id, ctx(profile("A", null), null, "1", null, "3"));
    verify(rates).save(same);
    assertThat(same.getRatingAvg()).isEqualByComparingTo("3");
  }

  @Test
  void rating_rowWithNoAverageYet_isFilled() {
    Mechanic m = existing();
    MechanicRate blank = MechanicRate.builder().mechanic(m).build();
    when(rates.findByMechanic(any())).thenReturn(Optional.of(blank));

    sync.sync(id, ctx(profile("A", null), null, "1", null, "2"));

    verify(rates).save(blank);
  }

  // ── retries and cache ──

  @Test
  void anUnchangedProfile_isSyncedOnce() {
    existing();

    MechanicContext.Mechanic c = ctx(profile("A", null), skill(null, null), "1", null, null);
    sync.sync(id, c);
    sync.sync(id, c);

    verify(mechanics, times(1)).findById(id);
  }

  @Test
  void aRaceOnFirstCreate_isRetriedOnce() {
    existing();
    TransactionTemplate flaky =
        new TransactionTemplate() {
          int calls;

          @Override
          public <T> T execute(TransactionCallback<T> action) {
            if (calls++ == 0) throw new DataIntegrityViolationException("dup");
            return action.doInTransaction(new SimpleTransactionStatus());
          }
        };
    MechanicProfileSync racing = new MechanicProfileSync(mechanics, skills, rates, flaky);

    racing.sync(id, ctx(profile("A", null), null, "1", null, null));

    verify(mechanics).findById(id);
  }

  @Test
  void aFailure_isSwallowed_andNotCached() {
    when(mechanics.findById(id)).thenThrow(new IllegalStateException("db down"));

    MechanicContext.Mechanic c = ctx(profile("A", null), null, "1", null, null);
    sync.sync(id, c);
    sync.sync(id, c);

    verify(mechanics, times(2)).findById(id);
  }
}
