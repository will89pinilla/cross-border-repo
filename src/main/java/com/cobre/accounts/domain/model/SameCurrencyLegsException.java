package com.cobre.accounts.domain.model;

/** A settlement whose two legs use the same currency; rule 2 ({@code SAME_CURRENCY}) must catch it first. */
public final class SameCurrencyLegsException extends DomainInvariantException {

    public SameCurrencyLegsException(String message) {
        super(message);
    }
}
