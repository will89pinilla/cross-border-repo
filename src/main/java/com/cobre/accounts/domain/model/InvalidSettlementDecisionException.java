package com.cobre.accounts.domain.model;

/** APPLIED without entryId, REJECTED without reasonCode, or a decision carrying both. */
public final class InvalidSettlementDecisionException extends DomainInvariantException {

    public InvalidSettlementDecisionException(String message) {
        super(message);
    }

}
