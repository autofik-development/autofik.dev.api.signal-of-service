package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.BidStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BidSosResponse(
        UUID bidId,
        BigDecimal price,
        String note,
        BidStatus status,
        LocalDateTime bidAt,
        LocalDateTime respondedAt,
        MechanicResponse mechanic
) {}
