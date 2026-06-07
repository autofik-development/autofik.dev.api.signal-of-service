package com.autofik.sos.dto.request;

import com.autofik.sos.domain.enums.BidStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateBidStatusRequest(
        @NotNull BidStatus status
) {}
