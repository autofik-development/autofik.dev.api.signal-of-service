package com.autofik.sos.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record BidCancelledEvent(
    UUID bidId, UUID sosId, UUID mechanicId, LocalDateTime cancelledAt) {}
