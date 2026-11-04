package com.cobre.accounts.adapters.out.persistence;

/** The seed of spec §6.3 is invalid: the service does not start, and the message says why. */
public class SeedConfigurationException extends RuntimeException {

    public SeedConfigurationException(String message) {
        super(message);
    }

    public SeedConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
