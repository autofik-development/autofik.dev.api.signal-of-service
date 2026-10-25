package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.MechanicRole;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A mechanic as anyone other than the mechanic themself may see them: enough for "who is nearby",
 * never how to reach them or exactly where they are. The contact fields are absent on purpose, so a
 * new caller cannot leak them by forgetting to null them out.
 *
 * <p>latitude/longitude are snapped to a 0.01° grid (see {@link com.autofik.sos.util.Geo#coarsen}),
 * or null when the read has no business knowing the position at all.
 *
 * <p>avgRate is SSO's rating as synced from mechanic-context. There is no count: SSO does not send
 * one, and the locally computed count stops moving once the rating endpoints are removed.
 */
public record MechanicSummaryResponse(
    UUID mechanicId,
    String fullName,
    MechanicRole mechanicRole,
    Integer serviceRadiusKm,
    BigDecimal avgRate,
    Boolean availableStatus,
    Double latitude,
    Double longitude) {}
