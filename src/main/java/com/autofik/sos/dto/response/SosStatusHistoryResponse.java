package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.UUID;

public record SosStatusHistoryResponse(
    UUID id,
    SosStatus fromStatus,
    SosStatus toStatus,
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime changeAt,
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime proposedAt,
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime approveAt,
    ApprovedStatus approvedStatus,
    MechanicResponse proposedBy,
    UserResponse approvedBy) {}
