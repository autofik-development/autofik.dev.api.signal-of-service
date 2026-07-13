package com.autofik.sos.exception;

/**
 * Custom runtime exception thrown when a required downstream service,
 * database, or external API integration is temporarily unreachable or failing.
 * This exception is intercepted by the {@link GlobalExceptionHandler} to
 * return a structured HTTP 503 Service Unavailable response to the client.
 */
public class ServiceUnavailableException extends RuntimeException {

    /**
     * Constructs a new ServiceUnavailableException with a specific descriptive error message.
     *
     * @param message the detail message explaining why the service is unavailable
     */
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
