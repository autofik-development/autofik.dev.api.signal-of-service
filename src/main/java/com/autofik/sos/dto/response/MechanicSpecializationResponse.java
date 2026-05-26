package com.autofik.sos.dto.response;

import com.autofik.sos.domain.Enum.ExperienceLevel;

public record MechanicSpecializationResponse(
        SpecializationResponse specialization,
        Integer yearOfExperience,
        ExperienceLevel experienceLevel
) {}
