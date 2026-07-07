package com.autofik.sos.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.Map;

public record ErrorResponse(
        int status,
        String error,
        String message,
        @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime timestamp,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> details
) {
    public ErrorResponse(int status, String error, String message, LocalDateTime timestamp) {
        this(status, error, message, timestamp, null);
    }
}
