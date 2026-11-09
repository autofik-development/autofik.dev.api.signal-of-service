package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.repository.MechanicRateRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.MechanicSkillRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.security.MechanicContext;
import com.autofik.sos.security.UserContext;
import com.autofik.sos.service.impl.MechanicProfileSync;
import com.autofik.sos.service.impl.UserProfileSync;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Profile-sync paths the other tests do not reach: unchanged vs changed images, null roles. */
class ProfileSyncBranchTest {

  private static final String OLD_IMAGE = "https://img.example/old.png";
  private static final String NEW_IMAGE = "https://img.example/new.png";

  private final TransactionTemplate tx =
      new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          return action.doInTransaction(new SimpleTransactionStatus());
        }
      };

  // ── driver ──

  private final UserRepository users = mock(UserRepository.class);
  private final VehicleRepository vehicles = mock(VehicleRepository.class);
  private final UserProfileSync userSync = new UserProfileSync(users, vehicles, tx);
  private final UUID userId = UUID.randomUUID();

  private User driver() {
    User u =
        User.builder()
            .userId(userId)
            .name("Sokha")
            .phone("+855111")
            .profileImage(OLD_IMAGE)
            .role("USER")
            .build();
    when(users.findById(userId)).thenReturn(Optional.of(u));
    when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    return u;
  }

  private static UserContext.User driverCtx(
      String role, String image, List<UserContext.ContextVehicle> cars) {
    return new UserContext.User(
        "x",
        role,
        null,
        true,
        true,
        "active",
        false,
        "+855111",
        new UserContext.Profile("Sokha", image),
        cars);
  }

  @Test
  void aHeaderWithNothingAtAll_isIgnored() {
    UserContext.User empty =
        new UserContext.User("x", null, null, true, true, "active", false, null, null, null);

    userSync.sync(userId, empty);

    verifyNoInteractions(users, vehicles);
  }

  @Test
  void driverImage_unchanged_notWritten() {
    driver();

    userSync.sync(userId, driverCtx("USER", OLD_IMAGE, null));

    verify(users, never()).save(any(User.class));
  }

  @Test
  void driverImage_changed_isWritten() {
    User u = driver();

    userSync.sync(userId, driverCtx("USER", NEW_IMAGE, null));

    assertThat(u.getProfileImage()).isEqualTo(NEW_IMAGE);
  }

  private Vehicle ownedCar(User owner) {
    Vehicle v =
        Vehicle.builder()
            .id("car-1")
            .user(owner)
            .carBrand("Toyota")
            .carModel("Camry")
            .carModelYear("2020")
            .licensePlate("P1")
            .image(OLD_IMAGE)
            .build();
    when(vehicles.findById("car-1")).thenReturn(Optional.of(v));
    return v;
  }

  private static UserContext.ContextVehicle car(String image) {
    return new UserContext.ContextVehicle("car-1", "Toyota", "Camry", "2020", null, "P1", image);
  }

  @Test
  void vehicleImage_unchanged_notWritten() {
    User u = driver();
    ownedCar(u);

    userSync.sync(userId, driverCtx("USER", OLD_IMAGE, List.of(car(OLD_IMAGE))));

    verify(vehicles, never()).save(any(Vehicle.class));
  }

  @Test
  void vehicleImage_changed_isWritten() {
    User u = driver();
    Vehicle v = ownedCar(u);

    userSync.sync(userId, driverCtx("USER", OLD_IMAGE, List.of(car(NEW_IMAGE))));

    assertThat(v.getImage()).isEqualTo(NEW_IMAGE);
    verify(vehicles).save(v);
  }

  // ── mechanic ──

  private final MechanicRepository mechanics = mock(MechanicRepository.class);
  private final MechanicProfileSync mechanicSync =
      new MechanicProfileSync(
          mechanics, mock(MechanicSkillRepository.class), mock(MechanicRateRepository.class), tx);
  private final UUID mechanicId = UUID.randomUUID();

  private Mechanic mechanic() {
    Mechanic m =
        Mechanic.builder()
            .mechanicId(mechanicId)
            .fullName("Vanna")
            .phoneNumber("+855999")
            .role("MECHANIC")
            .build();
    when(mechanics.findById(mechanicId)).thenReturn(Optional.of(m));
    when(mechanics.save(any(Mechanic.class))).thenAnswer(i -> i.getArgument(0));
    return m;
  }

  private static MechanicContext.Mechanic mechanicCtx(String role, String image) {
    return new MechanicContext.Mechanic(
        "x",
        role,
        null,
        true,
        true,
        "active",
        false,
        "+855999",
        "freelance",
        new MechanicContext.Profile("Vanna", null, image),
        null,
        null);
  }

  @Test
  void mechanicUpdate_withoutRoleOrImage_keepsBoth() {
    Mechanic m = mechanic();
    m.setProfileImage(OLD_IMAGE);

    mechanicSync.sync(mechanicId, mechanicCtx(null, null));

    assertThat(m.getRole()).isEqualTo("MECHANIC");
    assertThat(m.getProfileImage()).isEqualTo(OLD_IMAGE);
  }

  @Test
  void mechanicUpdate_withRoleAndImage_changesBoth() {
    Mechanic m = mechanic();

    mechanicSync.sync(mechanicId, mechanicCtx("garage", NEW_IMAGE));

    assertThat(m.getRole()).isEqualTo("GARAGE");
    assertThat(m.getProfileImage()).isEqualTo(NEW_IMAGE);
  }

  @Test
  void mechanicUpdate_blankRole_keepsTheStoredOne() {
    Mechanic m = mechanic();

    mechanicSync.sync(mechanicId, mechanicCtx("   ", null));

    assertThat(m.getRole()).isEqualTo("MECHANIC");
  }
}
