package com.autofik.sos.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler that intercepts exceptions thrown across the application and maps them
 * into consistent, user-friendly HTTP JSON responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  /** Handles cases where a requested database entity does not exist. Maps to HTTP 404 Not Found. */
  @ExceptionHandler(EntityNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleNotFound(EntityNotFoundException ex) {
    return new ErrorResponse(
        HttpStatus.NOT_FOUND.value(),
        HttpStatus.NOT_FOUND.getReasonPhrase(),
        ex.getMessage(),
        LocalDateTime.now());
  }

  /**
   * Handles static resource routing failures (e.g., missing API endpoints or static assets). Maps
   * to HTTP 404 Not Found.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleNoResource(NoResourceFoundException ex) {
    return new ErrorResponse(
        HttpStatus.NOT_FOUND.value(),
        HttpStatus.NOT_FOUND.getReasonPhrase(),
        ex.getMessage(),
        LocalDateTime.now());
  }

  /**
   * Handles a caller acting on something that is not theirs â€” another driver's SOS, another
   * mechanic's location, a vehicle they do not own. Maps to HTTP 403 Forbidden.
   *
   * <p>Deliberately vague: the message must not reveal whether the target exists.
   */
  @ExceptionHandler(AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ErrorResponse handleForbidden(AccessDeniedException ex) {
    log.warn("Access denied: {}", ex.getMessage());
    return new ErrorResponse(
        HttpStatus.FORBIDDEN.value(),
        HttpStatus.FORBIDDEN.getReasonPhrase(),
        "You are not allowed to perform this action",
        LocalDateTime.now());
  }

  /**
   * Handles business logic conflict states (e.g., trying to modify a resource that isn't ready).
   * Maps to HTTP 409 Conflict. A plain IllegalStateException is not mapped here: it is an internal
   * fault and falls through to the 500 handler.
   */
  @ExceptionHandler(ConflictException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ErrorResponse handleConflict(ConflictException ex) {
    return new ErrorResponse(
        HttpStatus.CONFLICT.value(),
        HttpStatus.CONFLICT.getReasonPhrase(),
        ex.getMessage(),
        LocalDateTime.now());
  }

  /**
   * Handles Jakarta/Hibernate Bean Validation failures when processing `@Valid` request bodies.
   * Extracts field names and error messages into a details map. Maps to HTTP 400 Bad Request.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
    Map<String, String> details =
        ex.getBindingResult().getFieldErrors().stream()
            .collect(
                Collectors.toMap(
                    FieldError::getField,
                    FieldError::getDefaultMessage,
                    (a, b) -> a, // If duplicate keys occur, keep the first validation error
                    LinkedHashMap::new)); // Maintains the field declaration order
    return new ErrorResponse(400, "Bad Request", "Validation failed", LocalDateTime.now(), details);
  }

  /**
   * Handles Bean Validation failures on controller method parameters â€” @RequestParam
   * and @PathVariable constraints enforced by @Validated. This is the counterpart to
   * MethodArgumentNotValidException above, which only covers @Valid request bodies. Without this
   * the violation reaches the Exception fallback and a client mistake is reported as a 500 with no
   * indication of which parameter was wrong. Maps to HTTP 400 Bad Request.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleConstraintViolation(ConstraintViolationException ex) {
    Map<String, String> details =
        ex.getConstraintViolations().stream()
            .collect(
                Collectors.toMap(
                    // Property path arrives as "updateLocation.lng" â€” keep only the parameter
                    // name
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
   * A database constraint rejected the write â€” typically a unique constraint hit by a duplicate
   * (a second feedback on the same SOS) or by two requests racing. The caller's request conflicts
   * with existing state, so it is a 409 rather than a server fault. Maps to HTTP 409 Conflict.
   *
   * <p>The message is fixed: the driver's message names the constraint, table and column.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ErrorResponse handleDataIntegrity(DataIntegrityViolationException ex) {
    log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
    return new ErrorResponse(
        HttpStatus.CONFLICT.value(),
        HttpStatus.CONFLICT.getReasonPhrase(),
        "The request conflicts with existing data",
        LocalDateTime.now());
  }

  /**
   * Request body that is not valid JSON, or has the wrong type for a field (a string where a UUID
   * or number belongs, an unknown enum value). A client mistake, not a server fault. Maps to HTTP
   * 400 Bad Request.
   *
   * <p>The message is fixed rather than Jackson's, which echoes the offending input and internal
   * class names back to the caller.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleUnreadableBody(HttpMessageNotReadableException ex) {
    return new ErrorResponse(400, "Bad Request", "Malformed request body", LocalDateTime.now());
  }

  /**
   * A path variable or query parameter that cannot be converted to its declared type (a non-UUID
   * id, an unknown enum value such as {@code ?status=NOPE}). Maps to HTTP 400 Bad Request.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return new ErrorResponse(
        400,
        "Bad Request",
        "Invalid value for parameter '" + ex.getName() + "'",
        LocalDateTime.now());
  }

  /**
   * A path variable that is present but blank, so conversion yields nothing (for example a UUID
   * segment of just a space). Maps to HTTP 400 Bad Request.
   */
  @ExceptionHandler(MissingPathVariableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleMissingPathVariable(MissingPathVariableException ex) {
    return new ErrorResponse(
        400,
        "Bad Request",
        "Invalid value for path parameter '" + ex.getVariableName() + "'",
        LocalDateTime.now());
  }

  /**
   * The request body's Content-Type is one the endpoint does not accept (a missing header, or
   * something other than JSON). Maps to HTTP 415 Unsupported Media Type.
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
  public ErrorResponse handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
    return new ErrorResponse(
        415, "Unsupported Media Type", "Unsupported content type", LocalDateTime.now());
  }

  /** The Accept header asks for something the endpoint cannot produce. Maps to HTTP 406. */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  @ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
  public ErrorResponse handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
    return new ErrorResponse(406, "Not Acceptable", "Not acceptable", LocalDateTime.now());
  }

  /** The path exists but not for this HTTP method. Maps to HTTP 405 Method Not Allowed. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
  public ErrorResponse handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
    return new ErrorResponse(405, "Method Not Allowed", "Method not allowed", LocalDateTime.now());
  }

  /** A required query parameter is absent. Maps to HTTP 400 Bad Request. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleMissingParameter(MissingServletRequestParameterException ex) {
    return new ErrorResponse(
        400,
        "Bad Request",
        "Missing required parameter '" + ex.getParameterName() + "'",
        LocalDateTime.now());
  }

  /** Handles invalid parameters passed to methods or controllers. Maps to HTTP 400 Bad Request. */
  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleBadArgument(IllegalArgumentException ex) {
    return new ErrorResponse(400, "Bad Request", ex.getMessage(), LocalDateTime.now());
  }

  /**
   * An unknown {@code sort=} property. Spring Data raises it while building the query, usually
   * wrapped in InvalidDataAccessApiUsageException. The library text names the entity class, so the
   * client gets a fixed message instead.
   */
  @ExceptionHandler({PropertyReferenceException.class, InvalidDataAccessApiUsageException.class})
  public ResponseEntity<ErrorResponse> handleBadSort(Exception ex) {
    boolean badSort =
        ex instanceof PropertyReferenceException
            || ex.getCause() instanceof PropertyReferenceException;
    if (!badSort) {
      return ResponseEntity.internalServerError().body(handleGeneral(ex));
    }
    return ResponseEntity.badRequest()
        .body(new ErrorResponse(400, "Bad Request", "Unknown sort property", LocalDateTime.now()));
  }

  /**
   * Handles custom downtime or external integration failures. Maps to HTTP 503 Service Unavailable.
   */
  @ExceptionHandler(ServiceUnavailableException.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public ErrorResponse handleServiceUnavailable(ServiceUnavailableException ex) {
    return new ErrorResponse(
        HttpStatus.SERVICE_UNAVAILABLE.value(),
        "Service Unavailable",
        ex.getMessage(),
        LocalDateTime.now());
  }

  /**
   * No database connection within the pool timeout: the pool is exhausted under load, or the
   * database is down. Opening a transaction raises CannotCreateTransactionException; a repository
   * call outside one raises DataAccessResourceFailureException. Either way the server is
   * temporarily unable, not broken, so 503 with Retry-After tells the app and the gateway to back
   * off and retry, where the catch-all's 500 told them nothing. Logged as one WARN line, not a
   * stack trace: under load this fires in bursts, and the cause is in the pool metrics, not the
   * stack.
   *
   * <p>The same answer for a wait that ran out of lock_timeout or a statement that ran out of
   * statement_timeout (see spring.datasource.hikari.connection-init-sql): a ticket held by a stuck
   * transaction, or a deadlock victim, is a retry for the caller, not a server fault.
   */
  @ExceptionHandler({
    CannotCreateTransactionException.class,
    DataAccessResourceFailureException.class,
    PessimisticLockingFailureException.class,
    QueryTimeoutException.class
  })
  public ResponseEntity<ErrorResponse> handleDatabaseUnavailable(Exception ex) {
    log.warn("Database unavailable, answering 503: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        // Matches the 5 s pool timeout: a retry sooner would mostly queue behind the same backlog.
        .header(HttpHeaders.RETRY_AFTER, "5")
        .body(
            new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                "The service is busy, please retry shortly",
                LocalDateTime.now()));
  }

  /** Handles API rate-limiting violations. Maps to HTTP 429 Too Many Requests. */
  @ExceptionHandler(TooManyRequestsException.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  public ErrorResponse handleRateLimit(TooManyRequestsException ex) {
    return new ErrorResponse(
        HttpStatus.TOO_MANY_REQUESTS.value(),
        "Too Many Requests",
        ex.getMessage(),
        LocalDateTime.now());
  }

  /**
   * Fallback handler for any unexpected system exceptions. Logs the full stack trace for debugging
   * and returns a generic safe message to the client. Maps to HTTP 500 Internal Server Error.
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ErrorResponse handleGeneral(Exception ex) {
    log.error("Unhandled exception", ex);
    return new ErrorResponse(
        HttpStatus.INTERNAL_SERVER_ERROR.value(),
        HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
        "Internal server error",
        LocalDateTime.now());
  }
}
