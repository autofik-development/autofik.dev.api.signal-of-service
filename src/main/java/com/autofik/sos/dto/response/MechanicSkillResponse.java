package com.autofik.sos.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record MechanicSkillResponse(
        UUID id,
        UUID mechanicId,
        List<String> skillTags,
        String vehicleSpecialization,
        LocalDateTime createdAt
) {}
