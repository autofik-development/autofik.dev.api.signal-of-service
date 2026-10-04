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
    MechanicResponse mechanic,
    // Only set on GET /sos/{id} for the owner, while the mechanic's completion request waits for
    // approval. It is the historyId PATCH /sos/completion needs, so a customer who missed the
    // COMPLETION_REQUESTED frame can still approve. Null otherwise.
    UUID pendingCompletionHistoryId,
    // When that request is approved automatically if the customer has not answered: the countdown
    // the app shows. Null whenever pendingCompletionHistoryId is null.
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en")
        LocalDateTime pendingCompletionApproveBy) {

  public SosRequestResponse withPendingCompletion(UUID historyId, LocalDateTime approveBy) {
    return new SosRequestResponse(
        sosId,
        problemType,
        description,
        locationAddress,
        status,
        latitude,
        longitude,
        broadcastRadiusKm,
        createdAt,
        updatedAt,
        biddingExpiresAt,
        jobStartedAt,
        jobCompletedAt,
        durationSeconds,
        rebroadcastCount,
        user,
        vehicle,
        mechanic,
        historyId,
        approveBy);
  }
}
