package com.autofik.sos.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateUserRequest(
        @NotBlank String name,
        @NotBlank String phone
) {}
