package com.autofik.sos.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record SosCreatedEvent(
    UUID sosId,
    UUID userId,
    UUID vehicleId,
    String problemType,
    String description,
    double latitude,
    double longitude,
    String locationAddress,
    LocalDateTime createdAt) {}
