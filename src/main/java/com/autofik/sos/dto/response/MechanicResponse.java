package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.MechanicRole;
import java.math.BigDecimal;
import java.util.UUID;

public record MechanicResponse(
    UUID mechanicId,
    String fullName,
    String phoneNumber,
    String email,
    MechanicRole mechanicRole,
    String referralCode,
    String address,
    Integer serviceRadiusKm,
    BigDecimal avgRate,
    Integer rateCount,
    Boolean availableStatus,
    Double latitude,
    Double longitude,
    String profileImage) {}
