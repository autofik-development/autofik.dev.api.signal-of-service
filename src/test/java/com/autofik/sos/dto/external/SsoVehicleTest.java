package com.autofik.sos.dto.external;

import com.autofik.sos.domain.enums.FuelType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSO sends {@code "petrol"}; the enum is {@code PETROL}. Parsing this strictly took down
 * POST /api/v1/sos entirely, which is the wrong failure mode for a value nothing matches on.
 */
class SsoVehicleTest {

    private SsoVehicle withFuel(String fuelType) {
        return new SsoVehicle(null, "Toyota", "Camry", "2023", fuelType, "1AB-2345");
    }

    @Test
    void lowercaseFuelTypeIsAccepted() {
        assertThat(withFuel("petrol").fuel()).isEqualTo(FuelType.PETROL);
    }

    @Test
    void mixedCaseAndPaddingAreAccepted() {
        assertThat(withFuel("  Diesel ").fuel()).isEqualTo(FuelType.DIESEL);
    }

    @Test
    void unknownFuelTypeIsNullRatherThanAnError() {
        assertThat(withFuel("hydrogen").fuel()).isNull();
    }

    @Test
    void missingFuelTypeIsNull() {
        assertThat(withFuel(null).fuel()).isNull();
        assertThat(withFuel("").fuel()).isNull();
    }
}
