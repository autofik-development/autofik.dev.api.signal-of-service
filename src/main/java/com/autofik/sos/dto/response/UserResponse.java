package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.UserStatus;
import java.util.UUID;

public record UserResponse(
    UUID userId,
    String name,
    String phone,
    UserStatus status,
    String refererCode,
    String profileImage) {}
