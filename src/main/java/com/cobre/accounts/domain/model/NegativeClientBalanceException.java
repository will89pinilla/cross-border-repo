package com.cobre.accounts.domain.model;

/** A CLIENT account would end with a negative balance (spec §3.4). */
public final class NegativeClientBalanceException extends DomainInvariantException {

    public NegativeClientBalanceException(String message) {
        super(message);
    }

}
