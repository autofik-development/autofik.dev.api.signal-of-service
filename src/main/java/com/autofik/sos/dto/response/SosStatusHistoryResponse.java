package com.autofik.sos.dto.response;

import com.autofik.sos.domain.Enum.ApprovedStatus;
import com.autofik.sos.domain.Enum.SosStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SosStatusHistoryResponse(
        UUID id,
        SosStatus fromStatus,
        SosStatus toStatus,
        LocalDateTime changeAt,
        LocalDateTime proposedAt,
        LocalDateTime approveAt,
        ApprovedStatus approvedStatus,
        MechanicResponse proposedBy,
        UserResponse approvedBy
) {}
