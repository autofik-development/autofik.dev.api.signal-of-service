package com.autofik.sos.dto.request;

import com.autofik.sos.domain.enums.ApprovedStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ApproveStatusChangeRequest(
    @NotNull UUID userId, @NotNull UUID historyId, @NotNull ApprovedStatus approvedStatus) {}
