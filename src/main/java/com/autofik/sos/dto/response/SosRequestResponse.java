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
    Integer broadcastRadiusKm,
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime createdAt,
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime updatedAt,
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime biddingExpiresAt,
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime jobStartedAt,
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime jobCompletedAt,
    Long durationSeconds,
    int rebroadcastCount,
    UserResponse user,
    VehicleResponse vehicle,
    MechanicResponse mechanic) {}
