package com.autofik.sos.dto.request;

import com.autofik.sos.domain.enums.FuelType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateVehicleRequest(
        @NotNull UUID userId,
        String carBrand,
        String carModel,
        String carModelYear,
        FuelType fuelType
) {}
