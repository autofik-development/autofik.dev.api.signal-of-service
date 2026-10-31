package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.FuelType;
import com.autofik.sos.domain.enums.UserStatus;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.security.UserContext;
import com.autofik.sos.service.impl.UserProfileSync;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The header is unsigned, so the sync's job is as much "refuse what is out of bounds" as "copy what
 * SSO says". Each bound and each thing it must not touch is pinned here.
 */
class UserProfileSyncTest {

  private final UserRepository users = mock(UserRepository.class);
  private final VehicleRepository vehicles = mock(VehicleRepository.class);

  // Runs the callback inline: the unit under test is the sync, not Spring's transaction manager.
  private final TransactionTemplate tx =
      new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          return action.doInTransaction(new SimpleTransactionStatus());
        }
      };

  private final UserProfileSync sync = new UserProfileSync(users, vehicles, tx);

  private final UUID id = UUID.randomUUID();

  private static UserContext.User ctx(
      String username, String phone, List<UserContext.ContextVehicle> vehicles) {
    return new UserContext.User(
        "ignored",
        "USER",
        null,
        true,
        true,
        "active",
        false,
        phone,
        username == null ? null : new UserContext.Profile(username, null),
        vehicles);
  }

  private static UserContext.ContextVehicle car(String id) {
    return new UserContext.ContextVehicle(id, "Toyota", "Camry", "2022", "petrol", "1A 2345", null);
  }

  private UserContext.User sample() {
    return ctx("bothtest15", "+855123456789", List.of(car("648123456789135")));
  }

  private void stubSavesReturnArgument() {
    when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void aHeaderWithNoProfile_touchesNoRepository() {
    sync.sync(id, ctx(null, null, null));

    verifyNoInteractions(users, vehicles);
  }

  @Test
  void aNewDriver_isCreatedWithItsVehicle_underSsosOwnId() {
    when(users.findById(id)).thenReturn(Optional.empty());
    when(users.findByPhone("+855123456789")).thenReturn(Optional.empty());
    when(vehicles.findById("648123456789135")).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(id, sample());

    ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
    verify(users).save(user.capture());
    assertThat(user.getValue().getUserId()).isEqualTo(id);
    assertThat(user.getValue().getName()).isEqualTo("bothtest15");
    assertThat(user.getValue().getPhone()).isEqualTo("+855123456789");
    assertThat(user.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);

    ArgumentCaptor<Vehicle> vehicle = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicles).save(vehicle.capture());
    assertThat(vehicle.getValue().getId()).isEqualTo("648123456789135");
    assertThat(vehicle.getValue().getUser().getUserId()).isEqualTo(id);
    assertThat(vehicle.getValue().getCarBrand()).isEqualTo("Toyota");
    assertThat(vehicle.getValue().getCarModelYear()).isEqualTo("2022");
    assertThat(vehicle.getValue().getFuelType()).isEqualTo(FuelType.PETROL);
    assertThat(vehicle.getValue().getLicensePlate()).isEqualTo("1A 2345");
  }

  @Test
  void profileAndVehicleImages_areStored_whenHttps_andDroppedWhenNot() {
    when(users.findById(id)).thenReturn(Optional.empty());
    when(users.findByPhone("+855123456789")).thenReturn(Optional.empty());
    when(vehicles.findById("v1")).thenReturn(Optional.empty());
    when(vehicles.findById("v2")).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(
        id,
        new UserContext.User(
            "ignored",
            "USER",
            null,
            true,
            true,
            "active",
            false,
            "+855123456789",
            new UserContext.Profile("bothtest15", "https://cdn.example/u/1.png"),
            List.of(
                new UserContext.ContextVehicle(
                    "v1", "Toyota", null, null, null, null, "https://cdn.example/v/1.png"),
                new UserContext.ContextVehicle(
                    "v2", "Honda", null, null, null, null, "javascript:alert(1)"))));

    ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
    verify(users).save(user.capture());
    assertThat(user.getValue().getProfileImage()).isEqualTo("https://cdn.example/u/1.png");
    ArgumentCaptor<Vehicle> vehicle = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicles, org.mockito.Mockito.times(2)).save(vehicle.capture());
    assertThat(vehicle.getAllValues())
        .extracting(Vehicle::getImage)
        .containsExactly("https://cdn.example/v/1.png", null);
  }

  @Test
  void anExistingDriver_isUpdated_withoutTouchingWhatSosOwns() {
    User existing =
        User.builder()
            .userId(id)
            .name("Old")
            .phone("+85500000000")
            .status(UserStatus.SUSPENDED)
            .refererCode("REF-1")
            .build();
    when(users.findById(id)).thenReturn(Optional.of(existing));
    when(users.findByPhone("+855123456789")).thenReturn(Optional.empty());
    when(vehicles.findById(any())).thenReturn(Optional.empty());

    sync.sync(id, sample());

    verify(users).save(existing);
    assertThat(existing.getName()).isEqualTo("bothtest15");
    assertThat(existing.getPhone()).isEqualTo("+855123456789");
    assertThat(existing.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    assertThat(existing.getRefererCode()).isEqualTo("REF-1");
  }

  @Test
  void anUnchangedProfile_costsNoSecondQuery() {
    when(users.findById(id)).thenReturn(Optional.empty());
    when(users.findByPhone(any())).thenReturn(Optional.empty());
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(id, sample());
    sync.sync(id, sample());

    verify(users, times(1)).findById(id);
  }

  @Test
  void aChangedProfile_isWrittenAgain() {
    when(users.findById(id)).thenReturn(Optional.empty());
    when(users.findByPhone(any())).thenReturn(Optional.empty());
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(id, sample());
    sync.sync(id, ctx("renamed", "+855123456789", List.of(car("648123456789135"))));

    verify(users, times(2)).findById(id);
  }

  @Test
  void noUsableName_createsTheDriverWithAPlaceholderName() {
    when(users.findById(id)).thenReturn(Optional.empty());
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(id, ctx(null, "+855123456789", List.of(car("1"))));

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getName()).isEqualTo("Driver");
    assertThat(saved.getValue().getPhone()).isEqualTo("+855123456789");
  }

  @Test
  void noUsablePhone_createsTheDriverWithAPlaceholderPhone_uniquePerDriver() {
    when(users.findById(id)).thenReturn(Optional.empty());
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(id, ctx("name", null, List.of(car("1"))));

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getName()).isEqualTo("name");
    assertThat(saved.getValue().getPhone()).isEqualTo("missing:" + id);
    verify(users, never()).findByPhone(any()); // nothing to release for a stand-in
  }

  @Test
  void aPlaceholderPhone_isReplacedWhenTheRealOneArrives() {
    User existing = User.builder().userId(id).name("name").phone("missing:" + id).build();
    when(users.findById(id)).thenReturn(Optional.of(existing));
    when(users.findByPhone("+855123456789")).thenReturn(Optional.empty());
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(id, ctx("name", "+855123456789", null));

    assertThat(existing.getPhone()).isEqualTo("+855123456789");
  }

  @Test
  void aPhoneOverTheBound_isNotStored_theDriverGetsAPlaceholder() {
    when(users.findById(id)).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(id, ctx("name", "+8551234567890123456789", null));

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getPhone()).doesNotContain("+8551234567890123456789");
  }

  @Test
  void aPhoneAnotherDriverHolds_isReleasedFromThem_andGivenToTheCaller() {
    UUID other = UUID.randomUUID();
    User holder = User.builder().userId(other).name("Other").phone("+855123456789").build();
    when(users.findById(id)).thenReturn(Optional.empty());
    when(users.findByPhone("+855123456789")).thenReturn(Optional.of(holder));
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(id, sample());

    verify(users).saveAndFlush(holder);
    assertThat(holder.getPhone()).isEqualTo("released:" + other);
    // users.phone is varchar(255): the placeholder must fit, and it is unique per user.
    assertThat(holder.getPhone().length()).isLessThanOrEqualTo(255);
    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getUserId()).isEqualTo(id);
    assertThat(saved.getValue().getPhone()).isEqualTo("+855123456789");
  }

  @Test
  void aPhoneTheCallerAlreadyHolds_isLeftAlone() {
    User self = User.builder().userId(id).name("bothtest15").phone("+855123456789").build();
    when(users.findById(id)).thenReturn(Optional.of(self));
    when(users.findByPhone("+855123456789")).thenReturn(Optional.of(self));
    when(vehicles.findById(any())).thenReturn(Optional.empty());

    sync.sync(id, sample());

    verify(users, never()).saveAndFlush(any());
    assertThat(self.getPhone()).isEqualTo("+855123456789");
  }

  @Test
  void aVehicleAnotherDriverOwns_isSkipped() {
    User stranger = User.builder().userId(UUID.randomUUID()).build();
    Vehicle theirs =
        Vehicle.builder().id("648123456789135").user(stranger).carBrand("Honda").build();
    when(users.findById(id)).thenReturn(Optional.empty());
    when(users.findByPhone(any())).thenReturn(Optional.empty());
    when(vehicles.findById("648123456789135")).thenReturn(Optional.of(theirs));
    stubSavesReturnArgument();

    sync.sync(id, sample());

    verify(vehicles, never()).save(any());
    assertThat(theirs.getCarBrand()).isEqualTo("Honda");
    assertThat(theirs.getUser()).isSameAs(stranger);
  }

  @Test
  void anOwnedVehicle_isUpdated() {
    User self = User.builder().userId(id).name("bothtest15").phone("+855123456789").build();
    Vehicle mine = Vehicle.builder().id("648123456789135").user(self).carBrand("Honda").build();
    when(users.findById(id)).thenReturn(Optional.of(self));
    when(users.findByPhone(any())).thenReturn(Optional.empty());
    when(vehicles.findById("648123456789135")).thenReturn(Optional.of(mine));

    sync.sync(id, sample());

    verify(vehicles).save(mine);
    assertThat(mine.getCarBrand()).isEqualTo("Toyota");
    assertThat(mine.getFuelType()).isEqualTo(FuelType.PETROL);
  }

  @Test
  void anUnknownFuelType_isStoredAsNull_andDoesNotBlockTheVehicle() {
    when(users.findById(id)).thenReturn(Optional.empty());
    when(users.findByPhone(any())).thenReturn(Optional.empty());
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(
        id,
        ctx(
            "name",
            "+855123456789",
            List.of(
                new UserContext.ContextVehicle("v1", "Toyota", null, null, "steam", null, null))));

    ArgumentCaptor<Vehicle> vehicle = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicles).save(vehicle.capture());
    assertThat(vehicle.getValue().getFuelType()).isNull();
  }

  @Test
  void aVehicleWithNoUsableId_isIgnored() {
    when(users.findById(id)).thenReturn(Optional.empty());
    when(users.findByPhone(any())).thenReturn(Optional.empty());
    stubSavesReturnArgument();

    sync.sync(id, ctx("name", "+855123456789", List.of(car(" "), car("x".repeat(65)))));

    verify(vehicles, never()).save(any());
  }

  @Test
  void aVehicleMissingFromTheList_isNeverDeleted() {
    User self = User.builder().userId(id).name("bothtest15").phone("+855123456789").build();
    when(users.findById(id)).thenReturn(Optional.of(self));
    when(users.findByPhone(any())).thenReturn(Optional.empty());

    sync.sync(id, ctx("bothtest15", "+855123456789", List.of()));

    verify(vehicles, never()).delete(any());
    verify(vehicles, never()).deleteById(any());
    verify(vehicles, never()).deleteAll();
  }

  @Test
  void twoFirstRequestsRacing_theSecondBecomesAnUpdate() {
    User created = User.builder().userId(id).name("other").phone("+85500000000").build();
    when(users.findById(id)).thenReturn(Optional.empty()).thenReturn(Optional.of(created));
    when(users.findByPhone(any())).thenReturn(Optional.empty());
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    doThrow(new DataIntegrityViolationException("duplicate"))
        .doAnswer(i -> i.getArgument(0))
        .when(users)
        .save(any(User.class));

    sync.sync(id, sample());

    assertThat(created.getName()).isEqualTo("bothtest15");
  }

  @Test
  void aFailure_neverEscapes_andIsRetriedNextTime() {
    when(users.findById(id)).thenThrow(new IllegalStateException("db down"));

    assertThatCode(() -> sync.sync(id, sample())).doesNotThrowAnyException();

    doAnswer(i -> Optional.empty()).when(users).findById(id);
    when(users.findByPhone(any())).thenReturn(Optional.empty());
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    stubSavesReturnArgument();
    sync.sync(id, sample());

    verify(users).save(any(User.class));
  }
}
