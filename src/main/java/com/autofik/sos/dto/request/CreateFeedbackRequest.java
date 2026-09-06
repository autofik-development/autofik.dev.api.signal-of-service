package com.autofik.sos.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateFeedbackRequest(
    @NotNull UUID userId,
    @NotNull UUID mechanicId,
    @NotNull UUID sosId,
    @NotNull @DecimalMin("1.0") @DecimalMax("5.0") BigDecimal rating,
    String comment) {}
