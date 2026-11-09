package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.repository.MechanicRateRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.MechanicSkillRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.security.MechanicContext;
import com.autofik.sos.security.UserContext;
import com.autofik.sos.service.impl.MechanicProfileSync;
import com.autofik.sos.service.impl.UserProfileSync;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** The SSO role recorded on users and mechanic rows, and the 31–100 character name fix. */
class ProfileSyncRoleTest {

  private final TransactionTemplate tx =
      new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          return action.doInTransaction(new SimpleTransactionStatus());
        }
      };

  // ── driver (user-context) ──

  private final UserRepository users = mock(UserRepository.class);
  private final VehicleRepository vehicles = mock(VehicleRepository.class);
  private final UserProfileSync userSync = new UserProfileSync(users, vehicles, tx);
  private final UUID driverId = UUID.randomUUID();

  private static UserContext.User driverCtx(String role, String username) {
    return new UserContext.User(
        "ignored",
        role,
        null,
        true,
        true,
        "active",
        false,
        username == null ? null : "+855111222333", // no username = identity-only header
        username == null ? null : new UserContext.Profile(username, null),
        null);
  }

  @Test
  void driver_created_storesUpperCasedRole() {
    when(users.findById(driverId)).thenReturn(Optional.empty());
    when(users.findByPhone(any())).thenReturn(Optional.empty());
    when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

    userSync.sync(driverId, driverCtx("customer", "Sokha"));

    var saved = org.mockito.ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getRole()).isEqualTo("CUSTOMER");
  }

  @Test
  void driver_roleChanged_updatesRow_sameRole_noWrite() {
    User existing = User.builder().userId(driverId).name("Sokha").phone("+855111222333").build();
    when(users.findById(driverId)).thenReturn(Optional.of(existing));
    when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

    userSync.sync(driverId, driverCtx("USER", "Sokha"));
    assertThat(existing.getRole()).isEqualTo("USER");

    userSync.sync(driverId, driverCtx("CUSTOMER", "Sokha"));
    assertThat(existing.getRole()).isEqualTo("CUSTOMER");
  }

  @Test
  void driver_blankOrTooLongRole_notStored_requestNotRefused() {
    User existing = User.builder().userId(driverId).name("Sokha").phone("+855111222333").build();
    when(users.findById(driverId)).thenReturn(Optional.of(existing));

    userSync.sync(driverId, driverCtx("   ", "Sokha"));
    userSync.sync(driverId, driverCtx("X".repeat(21), "Sokha"));

    assertThat(existing.getRole()).isNull();
    verify(users, never()).save(any(User.class));
  }

  @Test
  void driver_identityOnly_rowExists_roleFilledIn() {
    User existing = User.builder().userId(driverId).name("Sokha").phone("+855111222333").build();
    when(users.findById(driverId)).thenReturn(Optional.of(existing));
    when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

    userSync.sync(driverId, driverCtx("USER", null));

    assertThat(existing.getRole()).isEqualTo("USER");
    assertThat(existing.getName()).isEqualTo("Sokha");
  }

  @Test
  void driver_identityOnly_noRow_nothingCreated() {
    when(users.findById(driverId)).thenReturn(Optional.empty());

    userSync.sync(driverId, driverCtx("USER", null));

    verify(users, never()).save(any(User.class));
    verify(users, never()).saveAndFlush(any(User.class));
  }

  // ── mechanic (mechanic-context) ──

  private final MechanicRepository mechanics = mock(MechanicRepository.class);
  private final MechanicProfileSync mechanicSync =
      new MechanicProfileSync(
          mechanics, mock(MechanicSkillRepository.class), mock(MechanicRateRepository.class), tx);
  private final UUID mechanicId = UUID.randomUUID();

  private static MechanicContext.Mechanic mechanicCtx(
      String role, MechanicContext.Profile profile, String phone) {
    return new MechanicContext.Mechanic(
        "ignored",
        role,
        null,
        true,
        true,
        "active",
        false,
        phone,
        "freelance",
        profile,
        null,
        null);
  }

  @Test
  void mechanic_created_storesRole() {
    when(mechanics.findById(mechanicId)).thenReturn(Optional.empty());
    when(mechanics.save(any(Mechanic.class))).thenAnswer(i -> i.getArgument(0));

    mechanicSync.sync(
        mechanicId,
        mechanicCtx("MECHANIC", new MechanicContext.Profile("Vanna", "v@x.io", null), "+855999"));

    var saved = org.mockito.ArgumentCaptor.forClass(Mechanic.class);
    verify(mechanics).save(saved.capture());
    assertThat(saved.getValue().getRole()).isEqualTo("MECHANIC");
  }

  @Test
  void mechanic_garageRole_storedAsGarage() {
    when(mechanics.findById(mechanicId)).thenReturn(Optional.empty());
    when(mechanics.save(any(Mechanic.class))).thenAnswer(i -> i.getArgument(0));

    mechanicSync.sync(
        mechanicId,
        mechanicCtx("GARAGE", new MechanicContext.Profile("Vanna", null, null), "+855999"));

    var saved = org.mockito.ArgumentCaptor.forClass(Mechanic.class);
    verify(mechanics).save(saved.capture());
    assertThat(saved.getValue().getRole()).isEqualTo("GARAGE");
  }

  @Test
  void mechanic_identityOnly_noRow_nothingCreated() {
    when(mechanics.findById(mechanicId)).thenReturn(Optional.empty());

    mechanicSync.sync(mechanicId, mechanicCtx("MECHANIC", null, "+855999"));

    verify(mechanics, never()).save(any(Mechanic.class));
  }

  @Test
  void mechanic_identityOnly_rowExists_roleFilledIn() {
    Mechanic existing =
        Mechanic.builder().mechanicId(mechanicId).fullName("Vanna").phoneNumber("+855999").build();
    when(mechanics.findById(mechanicId)).thenReturn(Optional.of(existing));
    when(mechanics.save(any(Mechanic.class))).thenAnswer(i -> i.getArgument(0));

    mechanicSync.sync(mechanicId, mechanicCtx("MECHANIC", null, "+855999"));

    assertThat(existing.getRole()).isEqualTo("MECHANIC");
  }

  @Test
  void mechanic_thirtyOneToHundredCharName_createdWithFullName() {
    String longName = "K".repeat(31) + " " + "Sokha Chan Tola Vanna Rith Ly Pheap";
    assertThat(longName.length()).isBetween(31, 100);
    when(mechanics.findById(mechanicId)).thenReturn(Optional.empty());
    when(mechanics.save(any(Mechanic.class))).thenAnswer(i -> i.getArgument(0));

    mechanicSync.sync(
        mechanicId,
        mechanicCtx("MECHANIC", new MechanicContext.Profile(longName, null, null), "+855999"));

    var saved = org.mockito.ArgumentCaptor.forClass(Mechanic.class);
    verify(mechanics).save(saved.capture());
    assertThat(saved.getValue().getFullName()).isEqualTo(longName);
  }

  @Test
  void mechanic_overHundredCharName_notStored_noRowCreated() {
    when(mechanics.findById(mechanicId)).thenReturn(Optional.empty());

    mechanicSync.sync(
        mechanicId,
        mechanicCtx(
            "MECHANIC", new MechanicContext.Profile("N".repeat(101), null, null), "+855999"));

    verify(mechanics, never()).save(any(Mechanic.class));
  }

  @Test
  void mechanic_longerEmail_stored() {
    String email = "a".repeat(200) + "@example.com";
    when(mechanics.findById(mechanicId)).thenReturn(Optional.empty());
    when(mechanics.save(any(Mechanic.class))).thenAnswer(i -> i.getArgument(0));

    mechanicSync.sync(
        mechanicId,
        mechanicCtx("MECHANIC", new MechanicContext.Profile("Vanna", email, null), "+855999"));

    var saved = org.mockito.ArgumentCaptor.forClass(Mechanic.class);
    verify(mechanics).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo(email);
  }
}
