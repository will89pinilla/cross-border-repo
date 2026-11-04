package com.cobre.accounts.domain.model;

import java.util.Objects;

/** Account identifier. Ids with the {@code COBRE_} prefix are reserved for Cobre accounts (glossary §4). */
public record AccountId(String value) implements Comparable<AccountId> {

    static final String COBRE_PREFIX = "COBRE_";

    public AccountId {
        Objects.requireNonNull(value, "accountId");
        if (value.isBlank()) {
            throw new InvalidIdentifierException("Account id is blank");
        }
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    /** {@code COBRE_FX_POSITION_<currency>}. */
    public static AccountId fxPosition(CurrencyCode currency) {
        return new AccountId(COBRE_PREFIX + "FX_POSITION_" + currency.value());
    }

    /** {@code COBRE_FUNDING_<currency>}. */
    public static AccountId funding(CurrencyCode currency) {
        return new AccountId(COBRE_PREFIX + "FUNDING_" + currency.value());
    }

    /** Internal to Cobre: never usable from an event, never auto-created (spec §7.2, rule 3). */
    public boolean isCobreReserved() {
        return value.startsWith(COBRE_PREFIX);
    }

    /** Lock order of D12: ascending account_id. */
    @Override
    public int compareTo(AccountId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
