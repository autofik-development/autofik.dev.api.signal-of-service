package com.autofik.sos.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
        UUID userId,
        String name,
        String phone,
        String email,
        String referrerCode,
        @JsonFormat(pattern = "MMM d yyyy, h:mm a", locale = "en") LocalDateTime createdAt
) {}
