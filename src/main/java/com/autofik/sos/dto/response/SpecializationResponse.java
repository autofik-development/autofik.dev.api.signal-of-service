package com.autofik.sos.dto.response;

import java.util.UUID;

public record SpecializationResponse(
        UUID spcaId,
        String name
) {}
