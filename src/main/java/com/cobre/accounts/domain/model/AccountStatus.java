package com.cobre.accounts.domain.model;

/** Only ACTIVE accounts move money. CLOSED is final: the domain has no status transitions (spec §14). */
public enum AccountStatus {
    ACTIVE,
    BLOCKED,
    CLOSED
}
