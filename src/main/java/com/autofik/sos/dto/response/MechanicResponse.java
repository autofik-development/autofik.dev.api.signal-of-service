package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.ApprovalStatus;
import com.autofik.sos.domain.enums.MechanicRole;

import java.math.BigDecimal;
import java.util.UUID;

public record MechanicResponse(
        UUID mechanicId,
        UUID userId,
        String fullName,
        String phoneNumber,
        String email,
        MechanicRole mechanicRole,
        String referralCode,
        ApprovalStatus kycStatus,
        String address,
        Integer serviceRadiusKm,
        BigDecimal avgRate,
        Integer rateCount,
        Boolean availableStatus,
        ApprovalStatus verificationStatus,
        Double latitude,
        Double longitude
) {}
