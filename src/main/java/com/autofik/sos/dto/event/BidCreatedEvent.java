package com.autofik.sos.dto.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BidCreatedEvent(
    UUID bidId,
    UUID sosId,
    UUID mechanicId,
    BigDecimal price,
    Long estimatedDistanceM,
    Long estimatedDurationS,
    String estimatedDistanceText,
    String estimatedDurationText,
    LocalDateTime bidAt
) {}