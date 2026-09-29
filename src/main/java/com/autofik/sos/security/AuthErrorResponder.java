package com.autofik.sos.security;

import com.autofik.sos.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an auth failure in the same {@link ErrorResponse} shape {@code GlobalExceptionHandler}
 * produces.
 *
 * <p>Both places that turn a caller away sit outside the {@code @RestControllerAdvice} — a servlet
 * filter runs before it and an interceptor stops the request before a handler is reached — so
 * neither can throw its way to a formatted body. Shared here so the two cannot drift into answering
 * the same failure two different ways.
 */
@Component
@RequiredArgsConstructor
public class AuthErrorResponder {

  private final ObjectMapper objectMapper;

  public void write(HttpServletResponse response, HttpStatus status, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        new ErrorResponse(status.value(), status.getReasonPhrase(), message, LocalDateTime.now()));
  }
}
