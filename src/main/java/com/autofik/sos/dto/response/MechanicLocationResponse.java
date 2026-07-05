package com.autofik.sos.dto.response;

import java.time.Instant;

/**
 * On-demand read of a mechanic's current position — used for initial screen load only.
 * Continuous updates during an active job still arrive via WebSocket
 * (/topic/sos/{sosId}/location), fed from every GPS ping regardless of this endpoint.
 *
 * @param latitude  current latitude
 * @param longitude current longitude
 * @param asOf      UTC instant this position was last known (Redis read time, or Postgres
 *                  snapshot time when falling back); serialized as ISO-8601
 */
public record MechanicLocationResponse(
        double latitude,
        double longitude,
        Instant asOf
) {}
