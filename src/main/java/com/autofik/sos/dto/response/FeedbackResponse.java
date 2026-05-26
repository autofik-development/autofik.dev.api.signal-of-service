package com.autofik.sos.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record FeedbackResponse(
        UUID id,
        String comment,
        BigDecimal rating,
        LocalDateTime createdAt,
        UserResponse user,
        MechanicResponse mechanic
) {}
