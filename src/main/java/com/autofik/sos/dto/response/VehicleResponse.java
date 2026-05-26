package com.autofik.sos.dto.response;

import java.util.UUID;

public record VehicleResponse(
        UUID vehicleId,
        String plateNumber,
        String brand,
        String model,
        Integer year,
        String type,
        String fuelType,
        String color,
        Boolean isDefault
) {}
