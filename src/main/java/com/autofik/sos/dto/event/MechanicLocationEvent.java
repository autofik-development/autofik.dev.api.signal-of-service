package com.autofik.sos.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record MechanicLocationEvent(
        UUID sosId,
        UUID mechanicId,
        double latitude,
        double longitude,
        LocalDateTime timestamp
) {}
