package com.autofik.sos.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record ErrorResponse(
        int status,
        String error,
        String message,
        @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime timestamp
) {}
