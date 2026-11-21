package com.autofik.sos.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateBidRequest(
    @NotNull UUID mechanicId,
    @NotNull UUID sosId,
    // Required only while sos.features.bid-price-enabled is on; the service checks that.
    @DecimalMin("0.01") BigDecimal price) {}
