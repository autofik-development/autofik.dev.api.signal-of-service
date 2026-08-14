package com.autofik.sos.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler that intercepts exceptions thrown across the application
 * and maps them into consistent, user-friendly HTTP JSON responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles cases where a requested database entity does not exist.
     * Maps to HTTP 404 Not Found.
     */
    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(EntityNotFoundException ex) {
        return new ErrorResponse(HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(), LocalDateTime.now());
    }

    /**
     * Handles static resource routing failures (e.g., missing API endpoints or static assets).
     * Maps to HTTP 404 Not Found.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNoResource(NoResourceFoundException ex) {
        return new ErrorResponse(HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(), LocalDateTime.now());
    }

    /**
     * Handles a caller acting on something that is not theirs — another driver's SOS, another
     * mechanic's location, a vehicle they do not own.
     * Maps to HTTP 403 Forbidden.
     *
     * <p>Deliberately vague: the message must not reveal whether the target exists.
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleForbidden(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return new ErrorResponse(HttpStatus.FORBIDDEN.value(), HttpStatus.FORBIDDEN.getReasonPhrase(),
                "You are not allowed to perform this action", LocalDateTime.now());
    }

    /**
     * Handles business logic conflict states (e.g., bidding on a request that has already closed).
     * Maps to HTTP 409 Conflict.
     *
     * <p>Bound to the service's own exception rather than to {@link IllegalStateException}: any
     * library can throw the JDK type, and reporting an internal fault as a business conflict hides
     * a 500 that should have been logged and alerted on.
     */
    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(ConflictException ex) {
        return new ErrorResponse(HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getMessage(), LocalDateTime.now());
    }

    /**
     * Two writers touched the same row and this one lost. Nothing is wrong with the request — it
     * arrived a moment late — so the client is told to read the current state and try again.
     * Maps to HTTP 409 Conflict.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return new ErrorResponse(HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                "This request was updated by someone else. Refresh and try again.", LocalDateTime.now());
    }

    /**
     * Handles Jakarta/Hibernate Bean Validation failures when processing `@Valid` request bodies.
     * Extracts field names and error messages into a details map.
     * Maps to HTTP 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        FieldError::getDefaultMessage,
                        (a, b) -> a, // If duplicate keys occur, keep the first validation error
                        LinkedHashMap::new));     // Maintains the field declaration order
        return new ErrorResponse(400, "Bad Request", "Validation failed", LocalDateTime.now(), details);
    }

    /**
     * Handles Bean Validation failures on controller method parameters — @RequestParam and
     * @PathVariable constraints enforced by @Validated. This is the counterpart to
     * MethodArgumentNotValidException above, which only covers @Valid request bodies.
     * Without this the violation reaches the Exception fallback and a client mistake is
     * reported as a 500 with no indication of which parameter was wrong.
     * Maps to HTTP 400 Bad Request.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> details = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        // Property path arrives as "updateLocation.lng" — keep only the parameter name
                        v -> {
                            String path = v.getPropertyPath().toString();
                            int lastDot = path.lastIndexOf('.');
                            return lastDot < 0 ? path : path.substring(lastDot + 1);
                        },
                        ConstraintViolation::getMessage,
                        (a, b) -> a, // If the same parameter fails twice, keep the first message
                        LinkedHashMap::new));
        return new ErrorResponse(400, "Bad Request", "Validation failed", LocalDateTime.now(), details);
    }

    /**
     * Handles invalid parameters passed to methods or controllers.
     * Maps to HTTP 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadArgument(IllegalArgumentException ex) {
        return new ErrorResponse(400, "Bad Request", ex.getMessage(), LocalDateTime.now());
    }

    /**
     * Handles custom downtime or external integration failures.
     * Maps to HTTP 503 Service Unavailable.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse handleServiceUnavailable(ServiceUnavailableException ex) {
        return new ErrorResponse(HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable",
                ex.getMessage(), LocalDateTime.now());
    }

    /**
     * Handles API rate-limiting violations.
     * Maps to HTTP 429 Too Many Requests.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ErrorResponse handleRateLimit(TooManyRequestsException ex) {
        return new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests",
                ex.getMessage(), LocalDateTime.now());
    }

    /**
     * Fallback handler for any unexpected system exceptions.
     * Logs the full stack trace for debugging and returns a generic safe message to the client.
     * Maps to HTTP 500 Internal Server Error.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "Internal server error", LocalDateTime.now());
    }
}
