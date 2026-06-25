package com.autofik.sos.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record FeedbackResponse(
        UUID id,
        String comment,
        BigDecimal rating,
        @JsonFormat(pattern = "MMM d yyyy, h:mm a", locale = "en") LocalDateTime createdAt,
        UserResponse user,
        MechanicResponse mechanic
) {}
