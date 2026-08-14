package com.autofik.sos.exception;

/**
 * A request that is well-formed but disagrees with the current state of the world — bidding on a
 * closed request, accepting a bid that was already answered, completing a job twice. Maps to 409.
 *
 * <p>Extends {@link IllegalStateException} deliberately. The handler needs a type this service owns
 * so a stray {@code IllegalStateException} from a library is no longer reported to the client as a
 * business conflict, but every existing caller and test that catches the JDK type keeps working.
 */
public class ConflictException extends IllegalStateException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
