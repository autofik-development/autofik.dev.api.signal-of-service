package com.autofik.sos.dto.event;

import java.util.UUID;

public record SosNotificationEvent(
        String type,
        UUID recipientId,
        String recipientRole,
        UUID sosId,
        String title,
        String body
) {}
