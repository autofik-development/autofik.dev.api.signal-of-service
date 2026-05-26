package com.autofik.sos.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
        UUID userId,
        String name,
        String phone,
        String email,
        LocalDateTime createdAt
) {}
