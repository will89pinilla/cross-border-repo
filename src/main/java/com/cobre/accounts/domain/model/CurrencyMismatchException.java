package com.cobre.accounts.domain.model;

/** Two currencies were mixed: Money operations, a line on an account of another currency, or a debit/credit in another currency. */
public final class CurrencyMismatchException extends DomainInvariantException {

    public CurrencyMismatchException(String message) {
        super(message);
    }

}
