package com.cobre.accounts.domain.model;

/** An amount with more than 2 decimals (never rounded, D11) or a non-positive amount where one greater than 0 is required. */
public final class InvalidAmountException extends DomainInvariantException {

    public InvalidAmountException(String message) {
        super(message);
    }

    public InvalidAmountException(String message, Throwable cause) {
        super(message, cause);
    }
}
