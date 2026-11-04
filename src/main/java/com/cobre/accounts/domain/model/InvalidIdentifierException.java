package com.cobre.accounts.domain.model;

/** A blank or malformed identifier: currency code, account id or source event id. */
public final class InvalidIdentifierException extends DomainInvariantException {

    public InvalidIdentifierException(String message) {
        super(message);
    }

}
