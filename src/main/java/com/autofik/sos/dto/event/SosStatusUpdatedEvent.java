package com.autofik.sos.dto.event;

import com.autofik.sos.domain.enums.SosStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record SosStatusUpdatedEvent(
    UUID sosId,
    UUID mechanicId,
    UUID userId,
    SosStatus fromStatus,
    SosStatus toStatus,
    LocalDateTime changedAt) {}
