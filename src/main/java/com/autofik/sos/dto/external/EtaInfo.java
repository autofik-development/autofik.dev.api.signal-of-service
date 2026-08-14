package com.autofik.sos.dto.external;

/**
 * Live route progress for an SOS in flight: how far the mechanic still has to drive
 * and how long that should take.
 *
 * @param estimated true when derived from a straight-line fallback rather than a live
 *                  Google route, so the client can render it with an approximation marker.
 */
public record EtaInfo(
        long remainingDistanceM,
        long remainingDurationS,
        String remainingDistanceText,
        String remainingDurationText,
        boolean estimated
) {}
