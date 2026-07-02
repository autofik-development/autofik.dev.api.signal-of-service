package com.autofik.sos.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record MechanicResponse(
        UUID mechanicId,
        String name,
        String phone,
        String email,
        Boolean isAvailable,
        BigDecimal ratingAvg,
        Double latitude,
        Double longitude,
        String referrerCode
) {}
