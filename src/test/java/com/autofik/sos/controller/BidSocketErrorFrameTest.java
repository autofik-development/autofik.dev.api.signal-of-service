package com.autofik.sos.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.exception.BadRequestException;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.exception.TooManyRequestsException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Every exception type maps to the same status and fixed text the REST handler gives. */
class BidSocketErrorFrameTest {

  // Named like the Spring exceptions the controller recognises by simple name.
  static class MessageConversionException extends RuntimeException {
    MessageConversionException() {
      super("secret detail");
    }
  }

  static class MethodArgumentNotValidException extends RuntimeException {}

  static class MethodArgumentResolutionException extends RuntimeException {}

  private static Map<String, Object> frame(Exception ex) {
    return BidSocketController.errorFrame(ex, "cid");
  }

  @Test
  void statuses_matchTheRestHandler() {
    assertThat(frame(new EntityNotFoundException("n")).get("status")).isEqualTo(404);
    assertThat(frame(new AccessDeniedException("n")).get("status")).isEqualTo(403);
    assertThat(frame(new ConflictException("n")).get("status")).isEqualTo(409);
    assertThat(frame(new IllegalArgumentException("n")).get("status")).isEqualTo(400);
    assertThat(frame(new DataIntegrityViolationException("n")).get("status")).isEqualTo(409);
    assertThat(frame(new ConstraintViolationException(Set.of())).get("status")).isEqualTo(400);
    assertThat(frame(new RejectedExecutionException()).get("status")).isEqualTo(503);
    assertThat(frame(new ServiceUnavailableException("n")).get("status")).isEqualTo(503);
    assertThat(frame(new CannotCreateTransactionException("n")).get("status")).isEqualTo(503);
    assertThat(frame(new DataAccessResourceFailureException("n")).get("status")).isEqualTo(503);
    assertThat(frame(new TooManyRequestsException("n")).get("status")).isEqualTo(429);
    assertThat(frame(new MessageConversionException()).get("status")).isEqualTo(400);
    assertThat(frame(new MethodArgumentNotValidException()).get("status")).isEqualTo(400);
    assertThat(frame(new MethodArgumentResolutionException()).get("status")).isEqualTo(400);
    assertThat(frame(new IllegalStateException("boom")).get("status")).isEqualTo(500);
  }

  @Test
  void texts_neverEchoInternals() {
    assertThat(frame(new IllegalStateException("boom")).get("error"))
        .isEqualTo("Internal server error");
    assertThat(frame(new DataIntegrityViolationException("sql")).get("error"))
        .isEqualTo("The request conflicts with existing data");
    assertThat(frame(new CannotCreateTransactionException("pool")).get("error"))
        .isEqualTo("The service is busy, please retry shortly");
    assertThat(frame(new DataAccessResourceFailureException("pool")).get("error"))
        .isEqualTo("The service is busy, please retry shortly");
    assertThat(frame(new RejectedExecutionException("q")).get("error"))
        .isEqualTo("Too many bids in progress, retry shortly");
    assertThat(frame(new AccessDeniedException("who")).get("error"))
        .isEqualTo("You are not allowed to perform this action");
    assertThat(frame(new IllegalArgumentException("lib text")).get("error"))
        .isEqualTo("Invalid message");
    assertThat(frame(new MessageConversionException()).get("error")).isEqualTo("Invalid message");
    assertThat(frame(new BadRequestException("price must be positive")).get("error"))
        .isEqualTo("price must be positive");
    assertThat(frame(new BadRequestException(null)).get("error")).isEqualTo("Invalid message");
    assertThat(frame(new ConflictException(null)).get("error")).isEqualTo("Rejected");
    assertThat(frame(new ConflictException("already")).get("error")).isEqualTo("already");
  }

  private static Message<?> msg(Object payload) {
    return MessageBuilder.withPayload(payload).build();
  }

  private static Message<?> json(String body) {
    return msg(body.getBytes(StandardCharsets.UTF_8));
  }

  private final BidSocketController controller = new BidSocketController(null, null, null, null);

  private Object idFor(Message<?> m) {
    return controller.handle(new IllegalArgumentException("x"), m).get("clientMessageId");
  }

  @Test
  void clientMessageId_isEchoedOnlyWhenItIsAShortString() {
    assertThat(idFor(json("{\"clientMessageId\":\"abc\"}"))).isEqualTo("abc");
    assertThat(idFor(json("{\"clientMessageId\":\"" + "x".repeat(65) + "\"}"))).isNull();
    assertThat(idFor(json("{\"clientMessageId\":5}"))).isNull();
    assertThat(idFor(json("{\"other\":1}"))).isNull();
    assertThat(idFor(json("not json at all"))).isNull();
    assertThat(idFor(msg("a string payload"))).isNull();
  }
}
