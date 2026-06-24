package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.BidStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BidSosResponse(
        UUID bidId,
        BigDecimal price,
        String note,
        BidStatus status,
        Long estimatedDistanceM,
        Long estimatedDurationS,
        String estimatedDistanceText,
        String estimatedDurationText,
        @JsonFormat(pattern = "MMM d yyyy, h:mm a", locale = "en") LocalDateTime bidAt,
        @JsonFormat(pattern = "MMM d yyyy, h:mm a", locale = "en") LocalDateTime respondedAt,
        MechanicResponse mechanic
) {}
