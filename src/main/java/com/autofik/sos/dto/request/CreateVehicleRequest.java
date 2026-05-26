package com.autofik.sos.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CreateVehicleRequest(
        @NotNull UUID userId,
        @NotBlank String plateNumber,
        @NotBlank String brand,
        @NotBlank String model,
        @NotNull @Positive Integer year,
        String type,
        String fuelType,
        String color,
        Boolean isDefault
) {}
