package com.autofik.sos.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateMechanicRequest(
        @NotBlank String name,
        @NotBlank String phone,
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotNull Double latitude,
        @NotNull Double longitude
) {}
