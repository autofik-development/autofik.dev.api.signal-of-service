package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.FuelType;

import java.util.UUID;

public record VehicleResponse(
        UUID id,
        String carBrand,
        String carModel,
        String carModelYear,
        FuelType fuelType
) {}
