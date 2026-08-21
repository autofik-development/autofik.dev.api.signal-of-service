package com.autofik.sos.dto.request;

import com.autofik.sos.domain.enums.MechanicRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// The @Size caps mirror the column widths on the Mechanic entity. Without them an over-long value
// passes validation and dies in the database instead, which surfaced as a 500 rather than telling
// the client which field was too long.
public record CreateMechanicRequest(
        @NotBlank @Size(max = 30) String fullName,
        @NotBlank @Size(max = 20) String phoneNumber,
        @NotBlank @Email @Size(max = 30) String email,
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
