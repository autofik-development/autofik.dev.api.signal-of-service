package com.autofik.sos.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * What a client is told for each kind of failure. The contract: client mistakes are 4xx with a
 * fixed, safe message; only the catch-all is a 500, and it never repeats the exception text.
 */
class ExceptionMappingTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void entityNotFound_is404_withTheMessage() {
    ErrorResponse r =
        handler.handleNotFound(new EntityNotFoundException("SOS request not found: 1"));
    assertThat(r.status()).isEqualTo(404);
    assertThat(r.message()).isEqualTo("SOS request not found: 1");
  }

  @Test
  void conflictException_is409_andPlainIllegalState_isNot() throws Exception {
    assertThat(handler.handleConflict(new ConflictException("Bids closed")).status())
        .isEqualTo(409);
    // A JDK IllegalStateException is an internal fault: no handler but the catch-all takes it.
    Method m =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class)
            .resolveMethodByThrowable(new IllegalStateException("boom"));
    assertThat(m.getName()).isEqualTo("handleGeneral");
  }

  @Test
  void unexpectedException_is500_andDoesNotEchoTheCause() {
    ErrorResponse r =
        handler.handleGeneral(
            new IllegalStateException("jdbc:postgresql://db:5432 password=hunter2"));
    assertThat(r.status()).isEqualTo(500);
    assertThat(r.message())
        .isEqualTo("Internal server error")
        .doesNotContain("hunter2")
        .doesNotContain("jdbc");
  }

  @Test
  void malformedBody_isFixedMessage_notJacksonsText() {
    ErrorResponse r =
        handler.handleUnreadableBody(
            new HttpMessageNotReadableException(
                "Cannot deserialize com.autofik.sos.dto.request.CreateSosRequest from \"secret-input\"",
                new MockHttpInputMessage(new byte[0])));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.message()).isEqualTo("Malformed request body");
  }

  @Test
  void beanValidation_listsEachFieldOnce_inDeclarationOrder() throws Exception {
    BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
    binding.addError(new FieldError("request", "latitude", "must not be null"));
    binding.addError(new FieldError("request", "latitude", "second message ignored"));
    binding.addError(new FieldError("request", "userId", "must not be null"));
    Method m = ExceptionMappingTest.class.getDeclaredMethod("target", Object.class);

    ErrorResponse r =
        handler.handleValidation(
            new MethodArgumentNotValidException(new MethodParameter(m, 0), binding));

    assertThat(r.status()).isEqualTo(400);
    assertThat(r.details())
        .containsExactly(
            java.util.Map.entry("latitude", "must not be null"),
            java.util.Map.entry("userId", "must not be null"));
  }

  @SuppressWarnings("unused")
  private void target(Object o) {}

  @Test
  void constraintViolation_onAParameter_reportsTheParameterName_notTheMethodPath() {
    @SuppressWarnings("unchecked")
    ConstraintViolation<Object> v = mock(ConstraintViolation.class);
    Path path = mock(Path.class);
    when(path.toString()).thenReturn("updateLocation.lng");
    when(v.getPropertyPath()).thenReturn(path);
    when(v.getMessage()).thenReturn("must be less than or equal to 107.63");

    ErrorResponse r =
        handler.handleConstraintViolation(new ConstraintViolationException(Set.of(v)));

    assertThat(r.details()).containsEntry("lng", "must be less than or equal to 107.63");
  }

  @Test
  void transportLevelClientMistakes_getTheirOwnStatus() {
    assertThat(
            handler
                .handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException("text/plain"))
                .status())
        .isEqualTo(415);
    assertThat(
            handler.handleNotAcceptable(new HttpMediaTypeNotAcceptableException("nope")).status())
        .isEqualTo(406);
    assertThat(
            handler
                .handleMethodNotAllowed(
                    new HttpRequestMethodNotSupportedException("TRACE", List.of("GET")))
                .status())
        .isEqualTo(405);
    ErrorResponse missing =
        handler.handleMissingParameter(
            new MissingServletRequestParameterException("lat", "Double"));
    assertThat(missing.status()).isEqualTo(400);
    assertThat(missing.message()).contains("lat");
  }

  @Test
  void uploadOverTheLimit_is413_withTheLimitInMb() {
    ErrorResponse r =
        handler.handleUploadTooLarge(
            new org.springframework.web.multipart.MaxUploadSizeExceededException(
                10L * 1024 * 1024));

    assertThat(r.status()).isEqualTo(413);
    assertThat(r.message()).isEqualTo("File exceeds the 10 MB limit");
  }

  @Test
  void uploadOverAnUnknownLimit_is413_withoutANumber() {
    ErrorResponse r =
        handler.handleUploadTooLarge(
            new org.springframework.web.multipart.MaxUploadSizeExceededException(-1));

    assertThat(r.status()).isEqualTo(413);
    assertThat(r.message()).isEqualTo("File is too large");
  }

  @Test
  void multipartWithoutTheFilePart_is400_namingThePart() {
    ErrorResponse r =
        handler.handleMissingPart(
            new org.springframework.web.multipart.support.MissingServletRequestPartException(
                "file"));

    assertThat(r.status()).isEqualTo(400);
    assertThat(r.message()).isEqualTo("Missing required part 'file'");
  }

  /**
   * The two ways "no database connection within the pool timeout" surfaces: opening a transaction
   * (CannotCreateTransactionException) and a repository call outside one
   * (DataAccessResourceFailureException). Both used to fall into the catch-all as 500s under the
   * create-mix load test. They are the server being temporarily unable, so 503 + Retry-After.
   */
  @Test
  void databaseConnectionUnavailable_is503_withRetryAfter_andDoesNotEchoTheCause()
      throws Exception {
    ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
    Exception[] causes = {
      new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"),
      new DataAccessResourceFailureException(
          "Unable to acquire JDBC Connection [HikariPool-1 - Connection is not available, request"
              + " timed out after 5001ms (total=20, active=20, idle=0, waiting=181)]")
    };
    for (Exception ex : causes) {
      Method m = resolver.resolveMethod(ex);
      assertThat(m).as(ex.getClass().getSimpleName()).isNotNull();
      ResponseEntity<?> r = (ResponseEntity<?>) m.invoke(handler, ex);

      assertThat(r.getStatusCode().value()).isEqualTo(503);
      assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
      ErrorResponse body = (ErrorResponse) r.getBody();
      assertThat(body.status()).isEqualTo(503);
      assertThat(body.message()).doesNotContain("Hikari").doesNotContain("JDBC");
    }
  }

  @Test
  void domainExceptions_mapToTheirStatus() {
    assertThat(
            handler
                .handleServiceUnavailable(new ServiceUnavailableException("Medusa down"))
                .status())
        .isEqualTo(503);
    assertThat(handler.handleRateLimit(new TooManyRequestsException("slow down")).status())
        .isEqualTo(429);
    assertThat(handler.handleBadRequest(new BadRequestException("bad radius")).message())
        .isEqualTo("bad radius");
    // A library's own text (JTS, H3, UUID) is not echoed.
    ErrorResponse library =
        handler.handleBadArgument(new IllegalArgumentException("Invalid UUID string: x"));
    assertThat(library.status()).isEqualTo(400);
    assertThat(library.message()).isEqualTo("Invalid request");
  }
}
