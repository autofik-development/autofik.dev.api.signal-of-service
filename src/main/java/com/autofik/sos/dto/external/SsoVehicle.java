package com.autofik.sos.dto.external;

import com.autofik.sos.domain.enums.FuelType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Locale;
import java.util.UUID;

/**
 * A driver's vehicle as SSO holds it. Field names match what {@code POST /api/user/vehicles}
 * accepts, which is also what the local {@code vehicle} table already calls them — only {@code
 * licensePlate} is new here.
 *
 * <p>{@code GET /api/user/vehicles/:id} is scoped to the calling user, so a 403 or 404 from it is
 * the ownership check: there is no way to read a vehicle that is not the caller's.
 *
 * @param fuelType kept as the raw string SSO sent. It arrives lower-case ({@code "petrol"}) while
 *     the enum is upper-case, and this is the wrong place to be strict: a fuel type nobody
 *     recognises must not stop a stranded driver raising an SOS. Read it through {@link #fuel()},
 *     which normalises case and returns null for anything unknown.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SsoVehicle(
    @JsonAlias({"id", "vehicleId", "vehicle_id"}) UUID id,
    @JsonAlias({"carBrand", "car_brand", "brand"}) String carBrand,
    @JsonAlias({"carModel", "car_model", "model"}) String carModel,
    @JsonAlias({"carModelYear", "car_model_year", "year"}) String carModelYear,
    @JsonAlias({"fuelType", "fuel_type"}) String fuelType,
    @JsonAlias({"licensePlate", "license_plate", "plateNumber", "plate"}) String licensePlate) {

  public FuelType fuel() {
    if (fuelType == null || fuelType.isBlank()) return null;
    try {
      return FuelType.valueOf(fuelType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknownToUs) {
      return null;
    }
  }
}
