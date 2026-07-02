package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.MediaType;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.UUID;

public record SosMediaResponse(
        UUID mediaId,
        MediaType mediaType,
        String contentType,
        Long sizeBytes,
        String url,
        @JsonFormat(pattern = "MMM d yyyy, h:mm a", locale = "en") LocalDateTime uploadedAt
) {}
