package com.autofik.sos.dto.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A mechanic GPS ping on its way to the customer's live-tracking screen.
 *
 * The remaining* fields carry route progress toward the SOS location and are null when there is
 * no ETA to show — the mechanic has arrived, the job ended, or the route could not be resolved.
 * Position is always present, so the map keeps updating even without a countdown.
 *
 * @param timestamp Cambodian local time (+07:00). Carries the offset rather than being a bare
 *                  LocalDateTime so it reads correctly for users while staying unambiguous —
 *                  and so it does not silently shift if the service runs on a UTC host.
 */
public record MechanicLocationEvent(
        UUID sosId,
        UUID mechanicId,
        double latitude,
        double longitude,
        OffsetDateTime timestamp,
        Long remainingDistanceM,
        Long remainingDurationS,
        String remainingDistanceText,
        String remainingDurationText
) {}
