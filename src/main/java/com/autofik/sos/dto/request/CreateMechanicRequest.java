package com.autofik.sos.dto.request;

import com.autofik.sos.domain.enums.MechanicRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateMechanicRequest(
        @NotNull UUID userId,
        @NotBlank String fullName,
        @NotBlank String phoneNumber,
        @NotBlank @Email String email,
        @NotNull MechanicRole mechanicRole,
        String address,
        Integer serviceRadiusKm,
        @NotNull Double latitude,
        @NotNull Double longitude
) {}
