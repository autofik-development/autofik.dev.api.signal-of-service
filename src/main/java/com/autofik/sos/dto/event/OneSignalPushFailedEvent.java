package com.autofik.sos.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record OneSignalPushFailedEvent(
    UUID recipientId, Integer status, String error, LocalDateTime failedAt) {}
