package com.autofik.sos.dto.request;

import com.autofik.sos.domain.Enum.SosStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ProposeStatusChangeRequest(
        @NotNull UUID mechanicId,
        @NotNull UUID sosId,
        @NotNull SosStatus toStatus
) {}
