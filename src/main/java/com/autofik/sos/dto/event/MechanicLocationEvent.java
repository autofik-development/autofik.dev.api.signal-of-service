package com.autofik.sos.dto.event;

import java.time.Instant;
import java.util.UUID;

public record MechanicLocationEvent(
        UUID sosId,
        UUID mechanicId,
        double latitude,
        double longitude,
        Instant timestamp
) {}
