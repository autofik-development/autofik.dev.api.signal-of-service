package com.autofik.sos.exception;

/**
 * The request conflicts with the current state of a resource (ticket not open, bid already placed,
 * mechanic busy). Maps to HTTP 409.
 *
 * <p>Extends IllegalStateException only so existing callers and tests that catch that type keep
 * working; the handler maps this subclass alone, so a plain IllegalStateException from a library or
 * a bug still ends as a logged 500.
 */
public class ConflictException extends IllegalStateException {

  public ConflictException(String message) {
    super(message);
  }
}
