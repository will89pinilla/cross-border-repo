package com.cobre.accounts.application.port.in;

import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.AccountType;
import com.cobre.accounts.domain.model.CurrencyCode;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Read model of {@code GET /v1/accounts/{id}/balance} (spec §8.2). The balance is a plain
 * {@link BigDecimal} with scale 2, not {@code Money}: a Cobre balance is a SUM over its lines with no
 * cap of its own (spec §9), so it may exceed the 17 integer digits of an input amount.
 */
public record AccountBalance(
        AccountId accountId, AccountType type, AccountStatus status, CurrencyCode currency, BigDecimal balance) {

    public AccountBalance {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(balance, "balance");
        if (balance.scale() != 2) {
            throw new IllegalArgumentException("Balance must have scale 2: " + balance);
        }
    }
}
