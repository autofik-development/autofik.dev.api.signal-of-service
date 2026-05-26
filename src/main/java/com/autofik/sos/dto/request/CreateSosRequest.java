package com.autofik.sos.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateSosRequest(
        @NotNull UUID userId,
        @NotBlank String problemType,
        String description,
        @NotNull Double latitude,
        @NotNull Double longitude,
        @NotNull UUID vehicleId
) {}
