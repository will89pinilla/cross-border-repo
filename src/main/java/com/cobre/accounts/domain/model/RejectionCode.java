package com.cobre.accounts.domain.model;

/** Rejection codes of spec §7.2, declared in precedence order (rules 1 to 6). */
public enum RejectionCode {
    UNSUPPORTED_EVENT_TYPE,
    INVALID_EVENT,
    ACCOUNT_NOT_FOUND,
    CURRENCY_MISMATCH,
    ACCOUNT_INACTIVE,
    INSUFFICIENT_FUNDS
}
