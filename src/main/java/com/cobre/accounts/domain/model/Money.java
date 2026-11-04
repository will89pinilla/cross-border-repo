package com.cobre.accounts.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable amount with its currency, always at scale 2 (spec §3.4, D11).
 *
 * <p>An amount whose value needs more than 2 decimals is refused, never rounded: {@code 10.500}
 * becomes {@code 10.50} (same value), {@code 10.005} throws. PostgreSQL's numeric(19,2) would
 * silently round it, so this is the guard. Negative amounts are allowed because Cobre accounts can
 * go below zero; "greater than zero" is enforced by {@code EntryLine} and {@code PostSettlement}.
 *
 * <p>Precision is at most 19 (17 integer digits), the limit of numeric(19,2): a balance that would
 * not fit fails here, in the domain, instead of as an SQL error. Both limits are checked on the
 * stripped value before {@code setScale}, so a huge exponent such as {@code 1E+999999999} is
 * refused without being expanded. Rejecting such an amount as {@code INVALID_EVENT} is the file
 * reader's job (spec §7.2, {@code AMOUNT_TOO_LARGE}); here it is an invariant.
 */
public record Money(BigDecimal amount, CurrencyCode currency) implements Comparable<Money> {

    private static final int SCALE = 2;
    private static final int PRECISION = 19;
    private static final int INTEGER_DIGITS = PRECISION - SCALE;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > SCALE) {
            throw new InvalidAmountException("Amount has more than " + SCALE + " decimals: " + describe(amount));
        }
        long integerDigits = (long) stripped.precision() - stripped.scale();
        if (integerDigits > INTEGER_DIGITS) {
            throw new InvalidAmountException("Amount exceeds precision " + PRECISION + " ("
                    + INTEGER_DIGITS + " integer digits): " + describe(amount));
        }
        // Exact: no digit is lost, so no RoundingMode is needed (and none is ever used).
        amount = stripped.setScale(SCALE);
    }

    /** Plain text for normal amounts; scientific notation for huge exponents, to avoid expanding them. */
    private static String describe(BigDecimal amount) {
        return Math.abs((long) amount.scale()) > 64 ? amount.toString() : amount.toPlainString();
    }

    /** Parses the decimal text as-is, without binary floating point (D11). */
    public static Money of(String amount, CurrencyCode currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(
                    "Cannot operate " + currency + " with " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
