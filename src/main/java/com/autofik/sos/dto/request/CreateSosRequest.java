package com.autofik.sos.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateSosRequest(
    @NotNull UUID userId,
    @NotBlank String serviceGroupId,
    String description,
    @NotNull @DecimalMin("10.41") @DecimalMax("14.69") Double latitude,
    @NotNull @DecimalMin("102.5") @DecimalMax("107.63") Double longitude,
    @NotBlank @Size(max = 64) String vehicleId,
    @Min(5) @Max(20) Integer broadcastRadiusKm) {}
