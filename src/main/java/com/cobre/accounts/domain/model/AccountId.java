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

    /**
     * Lock order of D12: ascending account_id by Unicode code point. That is the byte order of
     * UTF-8, i.e. PostgreSQL's {@code COLLATE "C"}; {@link String#compareTo} (UTF-16 units) differs
     * from it outside the Basic Multilingual Plane.
     */
    @Override
    public int compareTo(AccountId other) {
        String a = value;
        String b = other.value;
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    @Override
    public String toString() {
        return value;
    }
}
