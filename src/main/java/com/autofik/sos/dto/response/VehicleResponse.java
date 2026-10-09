package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.FuelType;

public record VehicleResponse(
    String id, String carBrand, String carModel, String carModelYear, FuelType fuelType) {}
