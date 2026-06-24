package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.SosStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.UUID;

public record SosRequestResponse(
        UUID sosId,
        String problemType,
        String description,
        String locationAddress,
        SosStatus status,
        Double latitude,
        Double longitude,
        @JsonFormat(pattern = "MMM d yyyy, h:mm a", locale = "en") LocalDateTime createdAt,
        @JsonFormat(pattern = "MMM d yyyy, h:mm a", locale = "en") LocalDateTime updatedAt,
        UserResponse user,
        VehicleResponse vehicle,
        MechanicResponse mechanic
) {}