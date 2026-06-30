package com.autofik.sos.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateBidRequest(
        @NotNull UUID mechanicId,
        @NotNull UUID sosId,
        @NotNull @DecimalMin("0.01") BigDecimal price
) {}
