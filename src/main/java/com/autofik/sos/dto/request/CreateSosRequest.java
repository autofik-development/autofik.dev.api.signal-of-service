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
    @Size(max = 1000) String description,
    @NotNull @DecimalMin("10.41") @DecimalMax("14.69") Double latitude,
    @NotNull @DecimalMin("102.5") @DecimalMax("107.63") Double longitude,
    @NotBlank @Size(max = 64) String vehicleId,
    // 5–20 km is what the customer may choose. The system itself widens past this (auto-expand,
    // ceiling sos.auto-expand.max-km) when no mechanic responds — a customer request above 20 is
    // refused with that explanation, not silently clamped or silently accepted.
    @Min(value = 5, message = "broadcastRadiusKm must be at least 5 km")
        @Max(
            value = 20,
            message =
                "broadcastRadiusKm cannot exceed 20 km. The system widens the search radius beyond"
                    + " this automatically when no mechanic responds; you cannot request a wider radius"
                    + " yourself.")
        Integer broadcastRadiusKm) {}
