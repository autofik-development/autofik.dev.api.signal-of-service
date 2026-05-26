package com.autofik.sos.dto.response;

import com.autofik.sos.domain.Enum.SosStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SosRequestResponse(
        UUID sosId,
        String problemType,
        String description,
        SosStatus status,
        Double latitude,
        Double longitude,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        UserResponse user,
        VehicleResponse vehicle,
        MechanicResponse mechanic
) {}