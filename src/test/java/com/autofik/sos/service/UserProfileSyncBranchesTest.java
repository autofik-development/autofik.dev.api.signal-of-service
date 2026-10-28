package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.FuelType;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.security.UserContext;
import com.autofik.sos.service.impl.UserProfileSync;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The update paths and the odd inputs of {@link UserProfileSync}; the basics are in its own test.
 */
class UserProfileSyncBranchesTest {

  private final UserRepository users = mock(UserRepository.class);
  private final VehicleRepository vehicles = mock(VehicleRepository.class);
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
        username == null ? null : new UserContext.Profile(username),
        vehicles);
  }

  private static UserContext.ContextVehicle car(
      String id, String brand, String model, String year, String fuel, String plate) {
    return new UserContext.ContextVehicle(id, brand, model, year, fuel, plate);
  }

  private User existing() {
    User user = User.builder().userId(id).name("Old Name").phone("+855111").build();
    when(users.findById(id)).thenReturn(Optional.of(user));
    when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    return user;
  }

  private Vehicle ownedVehicle(User owner) {
    Vehicle vehicle =
        Vehicle.builder()
            .id("v1")
            .user(owner)
            .carBrand("Toyota")
            .carModel("Camry")
            .carModelYear("2020")
            .fuelType(FuelType.PETROL)
            .licensePlate("1A 1111")
            .build();
    when(vehicles.findById("v1")).thenReturn(Optional.of(vehicle));
    return vehicle;
  }

  @Test
  void aMissingDriverIdOrContext_touchesNothing() {
    sync.sync(null, ctx("name", "+855", null));
    sync.sync(id, null);

    verifyNoInteractions(users, vehicles);
  }

  @Test
  void aHeaderWithOnlyAnEmptyVehicleList_touchesNothing() {
    sync.sync(id, ctx(null, null, List.of()));

    verifyNoInteractions(users, vehicles);
  }

  @Test
  void aHeaderWithOnlyAPhone_isStillSynced() {
    User user = existing();

    sync.sync(id, ctx(null, "+855222", null));

    assertThat(user.getPhone()).isEqualTo("+855222");
    assertThat(user.getName()).isEqualTo("Old Name");
  }

  @Test
  void anUnchangedNameAndPhone_areNotWritten() {
    User user = existing();

    sync.sync(id, ctx("Old Name", "+855111", null));

    verify(users, never()).save(any(User.class));
    assertThat(user.getName()).isEqualTo("Old Name");
  }

  @Test
  void aChangedNameOnly_isWritten_andThePhoneIsLeftAlone() {
    User user = existing();

    sync.sync(id, ctx("New Name", "+855111", null));

    assertThat(user.getName()).isEqualTo("New Name");
    assertThat(user.getPhone()).isEqualTo("+855111");
    verify(users).save(user);
  }

  @Test
  void aPhoneHeldByTheSameDriver_isNotReleasedFromThemselves() {
    User user = existing();
    when(users.findByPhone("+855333")).thenReturn(Optional.of(user));

    sync.sync(id, ctx("Old Name", "+855333", null));

    assertThat(user.getPhone()).isEqualTo("+855333");
    verify(users, never()).saveAndFlush(any(User.class));
  }

  @Test
  void aVehicleWithEveryFieldChanged_isUpdatedOnce() {
    User owner = existing();
    Vehicle vehicle = ownedVehicle(owner);

    sync.sync(
        id,
        ctx("Old Name", null, List.of(car("v1", "Honda", "Civic", "2023", "diesel", "2B 2222"))));

    assertThat(vehicle.getCarBrand()).isEqualTo("Honda");
    assertThat(vehicle.getCarModel()).isEqualTo("Civic");
    assertThat(vehicle.getCarModelYear()).isEqualTo("2023");
    assertThat(vehicle.getFuelType()).isEqualTo(FuelType.DIESEL);
    assertThat(vehicle.getLicensePlate()).isEqualTo("2B 2222");
    verify(vehicles).save(vehicle);
  }

  @Test
  void aVehicleWithNothingNew_isNotWritten() {
    User owner = existing();
    Vehicle vehicle = ownedVehicle(owner);

    sync.sync(
        id,
        ctx("Old Name", null, List.of(car("v1", "Toyota", "Camry", "2020", "petrol", "1A 1111"))));

    verify(vehicles, never()).save(vehicle);
  }

  @Test
  void aVehicleWithEmptyFields_keepsWhatIsStored() {
    User owner = existing();
    Vehicle vehicle = ownedVehicle(owner);

    sync.sync(id, ctx("Old Name", null, List.of(car("v1", null, " ", null, null, ""))));

    assertThat(vehicle.getCarBrand()).isEqualTo("Toyota");
    assertThat(vehicle.getCarModel()).isEqualTo("Camry");
    assertThat(vehicle.getFuelType()).isEqualTo(FuelType.PETROL);
    verify(vehicles, never()).save(vehicle);
  }

  @Test
  void eachSingleChangedVehicleField_isEnoughToWrite() {
    for (UserContext.ContextVehicle changed :
        List.of(
            car("v1", "Honda", "Camry", "2020", "petrol", "1A 1111"),
            car("v1", "Toyota", "Civic", "2020", "petrol", "1A 1111"),
            car("v1", "Toyota", "Camry", "2023", "petrol", "1A 1111"),
            car("v1", "Toyota", "Camry", "2020", "hybrid", "1A 1111"),
            car("v1", "Toyota", "Camry", "2020", "petrol", "9Z 9999"))) {
      org.mockito.Mockito.reset(users, vehicles);
      User owner = existing();
      Vehicle vehicle = ownedVehicle(owner);

      sync.sync(UUID.randomUUID(), ctx("x", null, null)); // different driver: fingerprint differs
      sync.sync(id, ctx("Old Name", "+855111" + changed.carBrand(), List.of(changed)));

      verify(vehicles).save(vehicle);
    }
  }

  @Test
  void aVehicleOfAnotherDriver_isNotTouched() {
    existing();
    User someoneElse = User.builder().userId(UUID.randomUUID()).build();
    Vehicle theirs = ownedVehicle(someoneElse);

    sync.sync(
        id, ctx("Old Name", null, List.of(car("v1", "Honda", "Civic", "2023", "diesel", "X"))));

    assertThat(theirs.getCarBrand()).isEqualTo("Toyota");
    verify(vehicles, never()).save(any(Vehicle.class));
  }

  @Test
  void aNewVehicle_withAnUnknownOrBlankFuel_isStoredWithoutFuel() {
    existing();
    when(vehicles.findById(any())).thenReturn(Optional.empty());

    sync.sync(
        id,
        ctx(
            "Old Name",
            null,
            List.of(
                car("a", "Toyota", "Camry", "2020", "steam", "P1"),
                car("b", "Toyota", "Camry", "2020", "  ", "P2"),
                car("c", "Toyota", "Camry", "2020", null, "P3"),
                car("d", "Toyota", "Camry", "2020", "Plug-in Hybrid", "P4"))));

    ArgumentCaptor<Vehicle> saved = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicles, org.mockito.Mockito.times(4)).save(saved.capture());
    assertThat(saved.getAllValues()).extracting(Vehicle::getFuelType).containsOnlyNulls();
  }

  @Test
  void aVehicleWithNoIdOrAnOversizedId_isSkipped_andNullEntriesAreIgnored() {
    existing();
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    List<UserContext.ContextVehicle> list =
        new java.util.ArrayList<>(
            List.of(
                car(null, "Toyota", "Camry", "2020", "petrol", "P1"),
                car("x".repeat(65), "Toyota", "Camry", "2020", "petrol", "P2"),
                car("ok", "Toyota", "Camry", "2020", "petrol", "P3")));
    list.add(0, null);

    sync.sync(id, ctx("Old Name", null, list));

    ArgumentCaptor<Vehicle> saved = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicles).save(saved.capture());
    assertThat(saved.getValue().getId()).isEqualTo("ok");
  }

  @Test
  void onlyTheFirstTwentyVehicles_areSynced() {
    existing();
    when(vehicles.findById(any())).thenReturn(Optional.empty());
    List<UserContext.ContextVehicle> many =
        IntStream.range(0, 30)
            .mapToObj(i -> car("v" + i, "Toyota", "Camry", "2020", "petrol", "P" + i))
            .toList();

    sync.sync(id, ctx("Old Name", null, many));

    verify(vehicles, org.mockito.Mockito.times(20)).save(any(Vehicle.class));
  }
}
