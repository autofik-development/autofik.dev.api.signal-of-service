package com.autofik.sos.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record BidAcceptedEvent(
        UUID bidId,
        UUID sosId,
        UUID mechanicId,
        UUID userId,
        double latitude,
        double longitude,
        LocalDateTime acceptedAt
) {}
