package com.cobre.accounts.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Value object (spec §3.4): one account, one direction, an amount greater than 0 and its date.
 *
 * <p>Built only from the {@link Account} it moves, so the line's currency always matches its
 * account's (the same rule the composite FK of {@code entry_line} enforces in the database).
 */
public final class EntryLine {

    private final AccountId accountId;
    private final Direction direction;
    private final Money amount;
    private final Instant postedAt;

    private EntryLine(AccountId accountId, Direction direction, Money amount, Instant postedAt) {
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.postedAt = postedAt;
    }

    public static EntryLine of(Account account, Direction direction, Money amount, Instant postedAt) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(postedAt, "postedAt");
        if (!amount.isPositive()) {
            throw new InvalidAmountException("Line amount must be greater than 0: " + amount);
        }
        if (!amount.currency().equals(account.currency())) {
            throw new CurrencyMismatchException(
                    "Line in " + amount.currency() + " on account " + account.id() + " in " + account.currency());
        }
        return new EntryLine(account.id(), direction, amount, postedAt);
    }

    /** CREDIT positive, DEBIT negative: the contribution of this line to its account's balance (spec §9). */
    public Money signedAmount() {
        return direction == Direction.CREDIT ? amount : amount.negate();
    }

    public AccountId accountId() {
        return accountId;
    }

    public Direction direction() {
        return direction;
    }

    public Money amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return amount.currency();
    }

    public Instant postedAt() {
        return postedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntryLine other
                && accountId.equals(other.accountId)
                && direction == other.direction
                && amount.equals(other.amount)
                && postedAt.equals(other.postedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, direction, amount, postedAt);
    }

    @Override
    public String toString() {
        return "EntryLine[" + accountId + ", " + direction + ", " + amount + ", " + postedAt + "]";
    }
}
