package com.cobre.accounts.domain.model;

/** Money was moved on an account that is not ACTIVE (spec §3.4). */
public final class AccountNotActiveException extends DomainInvariantException {

    public AccountNotActiveException(String message) {
        super(message);
    }

}
