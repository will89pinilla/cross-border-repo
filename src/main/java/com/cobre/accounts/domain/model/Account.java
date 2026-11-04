package com.cobre.accounts.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root (spec §3.4). Immutable: {@link #debit} and {@link #credit} return a new account.
 *
 * <ul>
 *   <li>Id, currency and type never change; there are no status transitions, so CLOSED is final.
 *   <li>Only ACTIVE accounts move money.
 *   <li>A CLIENT balance is never negative.
 *   <li>Cobre accounts do not store a balance: it is the sum of their lines (D10), so it is empty
 *       here and a movement only checks currency and status.
 * </ul>
 */
public final class Account {

    private final AccountId id;
    private final CurrencyCode currency;
    private final AccountType type;
    private final AccountStatus status;
    private final Money balance; // null for Cobre accounts (D10)

    private Account(AccountId id, CurrencyCode currency, AccountType type, AccountStatus status, Money balance) {
        this.id = Objects.requireNonNull(id, "id");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.type = Objects.requireNonNull(type, "type");
        this.status = Objects.requireNonNull(status, "status");
        this.balance = balance;
    }

    public static Account client(AccountId id, CurrencyCode currency, AccountStatus status, Money balance) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(balance, "balance");
        if (id.isCobreReserved()) {
            throw new InvalidIdentifierException("A client account cannot use a COBRE_ id: " + id);
        }
        if (!balance.currency().equals(currency)) {
            throw new CurrencyMismatchException(
                    "Balance in " + balance.currency() + " for account " + id + " in " + currency);
        }
        requireNonNegative(id, balance);
        return new Account(id, currency, AccountType.CLIENT, status, balance);
    }

    /** {@code COBRE_FX_POSITION_<currency>}: receives what Cobre buys, pays what it sells. */
    public static Account fxPosition(CurrencyCode currency, AccountStatus status) {
        return new Account(AccountId.fxPosition(currency), currency, AccountType.COBRE_POSITION, status, null);
    }

    /** {@code COBRE_FUNDING_<currency>}: counterpart of the opening balances (D9). */
    public static Account funding(CurrencyCode currency, AccountStatus status) {
        return new Account(AccountId.funding(currency), currency, AccountType.COBRE_FUNDING, status, null);
    }

    public Account debit(Money amount) {
        requireMovable(amount);
        if (balance == null) {
            return this;
        }
        Money after = balance.minus(amount);
        requireNonNegative(id, after);
        return new Account(id, currency, type, status, after);
    }

    public Account credit(Money amount) {
        requireMovable(amount);
        if (balance == null) {
            return this;
        }
        return new Account(id, currency, type, status, balance.plus(amount));
    }

    private void requireMovable(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (!amount.currency().equals(currency)) {
            throw new CurrencyMismatchException(
                    "Movement in " + amount.currency() + " on account " + id + " in " + currency);
        }
        if (!amount.isPositive()) {
            throw new InvalidAmountException("Movement must be greater than 0: " + amount);
        }
        if (status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException("Account " + id + " is " + status);
        }
    }

    private static void requireNonNegative(AccountId id, Money balance) {
        if (balance.isNegative()) {
            throw new NegativeClientBalanceException("Client account " + id + " would be " + balance);
        }
    }

    public AccountId id() {
        return id;
    }

    public CurrencyCode currency() {
        return currency;
    }

    public AccountType type() {
        return type;
    }

    public AccountStatus status() {
        return status;
    }

    /** Stored balance of a CLIENT account; empty for Cobre accounts (D10). */
    public Optional<Money> balance() {
        return Optional.ofNullable(balance);
    }

    public boolean isClient() {
        return type == AccountType.CLIENT;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Account other
                && id.equals(other.id)
                && currency.equals(other.currency)
                && type == other.type
                && status == other.status
                && Objects.equals(balance, other.balance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, currency, type, status, balance);
    }

    @Override
    public String toString() {
        return "Account[" + id + ", " + currency + ", " + type + ", " + status
                + (balance == null ? "" : ", " + balance.amount().toPlainString()) + "]";
    }
}
