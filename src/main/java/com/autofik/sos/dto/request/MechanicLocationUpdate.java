package com.autofik.sos.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// STOMP payload for /app/mechanics/location. mechanicId comes from the session Principal
// (the clientId used at connect time), not this body — see WebSocketConfig.
public record MechanicLocationUpdate(
        @NotNull @DecimalMin("10.41") @DecimalMax("14.69") Double lat,
        @NotNull @DecimalMin("102.5") @DecimalMax("107.63") Double lng,
        UUID sosId
) {}
