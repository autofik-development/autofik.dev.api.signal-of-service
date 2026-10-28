package com.autofik.sos.exception;

/**
 * The request itself is wrong (a status this endpoint does not take, a file that is not an image),
 * and the message is written for the client. Maps to HTTP 400 and the message is echoed.
 *
 * <p>Extends IllegalArgumentException so callers and tests that catch that type keep working; only
 * this subclass is echoed. A plain IllegalArgumentException from a library (JTS, H3, UUID) is still
 * a 400 but the client gets a fixed text, not the library's.
 */
public class BadRequestException extends IllegalArgumentException {

  public BadRequestException(String message) {
    super(message);
  }

  public BadRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
