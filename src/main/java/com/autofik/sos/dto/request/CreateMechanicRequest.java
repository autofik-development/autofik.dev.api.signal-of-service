package com.autofik.sos.dto.request;

import com.autofik.sos.domain.enums.MechanicRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateMechanicRequest(
        @NotBlank String fullName,
        @NotBlank String phoneNumber,
        @NotBlank @Email String email,
        @NotNull MechanicRole mechanicRole,
        String address,
        Integer serviceRadiusKm,
        @NotNull @DecimalMin("10.41") @DecimalMax("14.69") Double latitude,
        @NotNull @DecimalMin("102.5") @DecimalMax("107.63") Double longitude,

        // Optional at onboarding, but dispatch joins on the skill table: a mechanic with no skill
        // entry is never matched to an SOS. Supply them here, or add them later via
        // POST /api/v1/mechanics/{mechanicId}/skills.
        @Valid List<CreateMechanicSkillRequest> skills
) {}
