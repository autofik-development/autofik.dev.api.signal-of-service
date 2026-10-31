package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.FuelType;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.security.UserContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps this service's copy of a driver and their vehicles in step with SSO, from the profile the
 * gateway puts in the {@code user-context} header: creates the rows the first time the driver is
 * seen, and updates them when SSO's values change. Without this {@code POST /sos} answers 404 for
 * every new driver.
 *
 * <p>Synced: name, phone, and each vehicle's brand, model, year, fuel type and plate. Never
 * touched: status and referral code. Vehicles are never deleted, because SOS tickets point at them.
 *
 * <p>The header is unsigned, so every value is bounded before it is stored. A value that fails a
 * bound is skipped with a warning, not stored and not a reason to refuse the request: this runs
 * before the controller, and a profile glitch must not stop a driver using the app.
 *
 * <p>{@code users.phone} is unique. When the phone in the header belongs to another user row, that
 * row's phone is released (replaced by a placeholder) so the caller can take it. That is only as
 * safe as the header is unforgeable, so this must not be deployed before the SOS port is reachable
 * only from the internal gateway.
 */
@Component
@Slf4j
public class UserProfileSync {

  private static final int MAX_NAME = 100;
  private static final int MAX_PHONE = 20;
  private static final int MAX_VEHICLES = 20;
  private static final int MAX_VEHICLE_ID = 64;
  private static final int MAX_VEHICLE_FIELD = 50;

  /** What a released phone becomes; unique per user because it carries that user's id. */
  static final String RELEASED_PHONE_PREFIX = "released:";

  /** Stand-ins for a profile with no usable name or phone, so the driver can still raise an SOS. */
  static final String PLACEHOLDER_NAME = "Driver";

  static final String MISSING_PHONE_PREFIX = "missing:";

  private final UserRepository userRepository;
  private final VehicleRepository vehicleRepository;
  private final TransactionTemplate tx;

  // What was last written per driver, so a request whose profile has not changed costs no query.
  // ponytail: per instance, same trade-off as MechanicProfileSync.
  private final Cache<UUID, String> lastSynced =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(10)).build();

  public UserProfileSync(
      UserRepository userRepository, VehicleRepository vehicleRepository, TransactionTemplate tx) {
    this.userRepository = userRepository;
    this.vehicleRepository = vehicleRepository;
    this.tx = tx;
  }

  /** Never throws. {@code userId} is the caller the gateway headers resolved to. */
  public void sync(UUID userId, UserContext.User ctx) {
    if (userId == null
        || ctx == null
        || (ctx.profile() == null
            && ctx.phoneNumber() == null
            && (ctx.vehicles() == null || ctx.vehicles().isEmpty()))) {
      return;
    }
    try {
      Snapshot snapshot = Snapshot.of(ctx);
      String fingerprint = snapshot.fingerprint();
      if (fingerprint.equals(lastSynced.getIfPresent(userId))) {
        return;
      }
      try {
        tx.executeWithoutResult(status -> apply(userId, snapshot));
      } catch (DataIntegrityViolationException raced) {
        // Two first requests both found no row; the other one created it, so this is now an update.
        tx.executeWithoutResult(status -> apply(userId, snapshot));
      }
      lastSynced.put(userId, fingerprint);
    } catch (Exception e) {
      lastSynced.invalidate(userId);
      log.warn("Could not sync driver {} from the gateway profile: {}", userId, e.toString());
    }
  }

  private void apply(UUID userId, Snapshot s) {
    User user = userRepository.findById(userId).orElse(null);
    if (user == null) {
      // A bad SSO or gateway release must not block an emergency: create the row with stand-ins
      // and let the real values replace them on a later request (the update branch below).
      if (s.name == null || s.phone == null) {
        log.warn(
            "Driver {} created with a placeholder: the profile has no usable name or phone",
            userId);
      }
      String name = s.name != null ? s.name : PLACEHOLDER_NAME;
      String phone = s.phone != null ? s.phone : MISSING_PHONE_PREFIX + userId;
      if (s.phone != null) {
        releasePhoneHeldByAnother(s.phone, userId);
      }
      user =
          userRepository.save(
              User.builder().userId(userId).name(name).phone(phone).profileImage(s.image).build());
      log.info("Created driver {} from the gateway profile", userId);
    } else {
      boolean changed = false;
      if (s.name != null && !s.name.equals(user.getName())) {
        user.setName(s.name);
        changed = true;
      }
      if (s.phone != null && !s.phone.equals(user.getPhone())) {
        releasePhoneHeldByAnother(s.phone, userId);
        user.setPhone(s.phone);
        changed = true;
      }
      if (s.image != null && !s.image.equals(user.getProfileImage())) {
        user.setProfileImage(s.image);
        changed = true;
      }
      if (changed) {
        userRepository.save(user);
      }
    }
    for (VehicleSnap v : s.vehicles) {
      syncVehicle(user, v);
    }
  }

  /** The phone column is unique: move another row's claim on this number out of the way. */
  private void releasePhoneHeldByAnother(String phone, UUID userId) {
    userRepository
        .findByPhone(phone)
        .filter(other -> !other.getUserId().equals(userId))
        .ifPresent(
            other -> {
              other.setPhone(RELEASED_PHONE_PREFIX + other.getUserId());
              userRepository.saveAndFlush(other);
              // Never the number itself: this is the log of a header we cannot trust.
              log.warn(
                  "Released the phone of driver {} because driver {} now presents it",
                  other.getUserId(),
                  userId);
            });
  }

  private void syncVehicle(User owner, VehicleSnap v) {
    Vehicle vehicle = vehicleRepository.findById(v.id).orElse(null);
    if (vehicle == null) {
      vehicleRepository.save(
          Vehicle.builder()
              .id(v.id)
              .user(owner)
              .carBrand(v.brand)
              .carModel(v.model)
              .carModelYear(v.year)
              .fuelType(v.fuel)
              .licensePlate(v.plate)
              .image(v.image)
              .build());
      return;
    }
    if (!vehicle.getUser().getUserId().equals(owner.getUserId())) {
      log.warn(
          "Vehicle {} belongs to another driver; not updated from {}", v.id, owner.getUserId());
      return;
    }
    boolean changed = false;
    if (v.brand != null && !v.brand.equals(vehicle.getCarBrand())) {
      vehicle.setCarBrand(v.brand);
      changed = true;
    }
    if (v.model != null && !v.model.equals(vehicle.getCarModel())) {
      vehicle.setCarModel(v.model);
      changed = true;
    }
    if (v.year != null && !v.year.equals(vehicle.getCarModelYear())) {
      vehicle.setCarModelYear(v.year);
      changed = true;
    }
    if (v.fuel != null && v.fuel != vehicle.getFuelType()) {
      vehicle.setFuelType(v.fuel);
      changed = true;
    }
    if (v.plate != null && !v.plate.equals(vehicle.getLicensePlate())) {
      vehicle.setLicensePlate(v.plate);
      changed = true;
    }
    if (v.image != null && !v.image.equals(vehicle.getImage())) {
      vehicle.setImage(v.image);
      changed = true;
    }
    if (changed) {
      vehicleRepository.save(vehicle);
    }
  }

  private record VehicleSnap(
      String id,
      String brand,
      String model,
      String year,
      FuelType fuel,
      String plate,
      String image) {}

  /** The header's profile after every bound has been applied; a failed bound leaves a null. */
  private static final class Snapshot {
    String name;
    String phone;
    String image;
    List<VehicleSnap> vehicles = List.of();

    static Snapshot of(UserContext.User ctx) {
      Snapshot s = new Snapshot();
      if (ctx.profile() != null) {
        s.name = MechanicProfileSync.bounded("username", ctx.profile().username(), MAX_NAME);
        s.image = MechanicProfileSync.imageUrl("profileImage", ctx.profile().profileImage());
      }
      s.phone = MechanicProfileSync.bounded("phoneNumber", ctx.phoneNumber(), MAX_PHONE);
      if (ctx.vehicles() != null) {
        s.vehicles =
            ctx.vehicles().stream()
                .limit(MAX_VEHICLES)
                .filter(Objects::nonNull)
                .map(Snapshot::vehicle)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(VehicleSnap::id))
                .toList();
      }
      return s;
    }

    private static VehicleSnap vehicle(UserContext.ContextVehicle v) {
      String id = MechanicProfileSync.bounded("vehicle id", v.id(), MAX_VEHICLE_ID);
      if (id == null) {
        return null;
      }
      return new VehicleSnap(
          id,
          MechanicProfileSync.bounded("carBrand", v.carBrand(), MAX_VEHICLE_FIELD),
          MechanicProfileSync.bounded("carModel", v.carModel(), MAX_VEHICLE_FIELD),
          MechanicProfileSync.bounded("carModelYear", v.carModelYear(), MAX_VEHICLE_FIELD),
          fuel(v.fuelType()),
          MechanicProfileSync.bounded("licensePlate", v.licensePlate(), MAX_VEHICLE_FIELD),
          MechanicProfileSync.imageUrl("vehicle image", v.image()));
    }

    private static FuelType fuel(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      try {
        return FuelType.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
      } catch (IllegalArgumentException unknown) {
        log.warn("Profile fuelType is not a known value; not stored");
        return null;
      }
    }

    String fingerprint() {
      StringBuilder sb = new StringBuilder();
      sb.append(name).append('|').append(phone).append('|').append(image);
      for (VehicleSnap v : vehicles) {
        sb.append('|').append(v);
      }
      return sb.toString();
    }
  }
}
