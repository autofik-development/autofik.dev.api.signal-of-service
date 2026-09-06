package com.autofik.sos.dto.request;

import com.autofik.sos.domain.enums.BidStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UpdateBidStatusRequest(@NotNull UUID userId, @NotNull BidStatus status) {}
