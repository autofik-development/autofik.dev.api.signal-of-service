package com.autofik.sos.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record SosMediaUploadedEvent(
    UUID sosId, UUID mediaId, String uploadedByRole, UUID uploadedById, LocalDateTime uploadedAt) {}
