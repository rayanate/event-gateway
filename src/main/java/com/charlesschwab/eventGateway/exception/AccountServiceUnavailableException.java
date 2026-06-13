package com.charlesschwab.eventGateway.exception;

/**
 * Signals that the Account Service is unavailable or an outbound call failed.
 */
public class AccountServiceUnavailableException extends RuntimeException {
    public AccountServiceUnavailableException(String message) {
        super(message);
    }

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

