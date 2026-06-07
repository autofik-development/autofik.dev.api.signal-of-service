package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.ExperienceLevel;

public record MechanicSpecializationResponse(
        SpecializationResponse specialization,
        Integer yearOfExperience,
        ExperienceLevel experienceLevel
) {}
