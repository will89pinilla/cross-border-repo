package com.cobre.accounts.domain.model;

/** A journal entry with fewer than two lines, or whose debits and credits differ in some currency. */
public final class UnbalancedEntryException extends DomainInvariantException {

    public UnbalancedEntryException(String message) {
        super(message);
    }

}
