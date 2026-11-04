package com.cobre.accounts.application.port.out;

/**
 * The event already has a decision, or an entry, written by another transaction (spec §7.3): the
 * unique key rejected the insert. The transaction is rolled back and never retried; the caller
 * reports the event as a duplicate (D6).
 */
public class DecisionAlreadyExistsException extends RuntimeException {

    private final String sourceEventId;

    public DecisionAlreadyExistsException(String sourceEventId, Throwable cause) {
        super("Event " + sourceEventId + " already has a decision", cause);
        this.sourceEventId = sourceEventId;
    }

    public String sourceEventId() {
        return sourceEventId;
    }
}
