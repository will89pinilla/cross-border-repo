package com.cobre.accounts.domain.model;

/**
 * A domain invariant was violated (spec §3.4). This is a programming error, never a business
 * rejection: business rejections are values ({@code PostingResult.Rejected}).
 */
public abstract sealed class DomainInvariantException extends RuntimeException
        permits CurrencyMismatchException, InvalidAmountException, InvalidIdentifierException,
                AccountNotActiveException, NegativeClientBalanceException, UnbalancedEntryException,
                InvalidSettlementDecisionException, SameCurrencyLegsException {

    protected DomainInvariantException(String message) {
        super(message);
    }

    protected DomainInvariantException(String message, Throwable cause) {
        super(message, cause);
    }
}
