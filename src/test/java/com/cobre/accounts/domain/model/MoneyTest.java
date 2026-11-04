package com.cobre.accounts.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Invariants of {@code Money} and {@code CurrencyCode} (spec §3.4, D11). */
class MoneyTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @Test
    void currencyCode_acceptsThreeUppercaseLetters() {
        assertThat(CurrencyCode.of("MXN").value()).isEqualTo("MXN");
    }

    @Test
    void currencyCode_rejectsLowercase() {
        assertThatThrownBy(() -> CurrencyCode.of("mxn")).isInstanceOf(InvalidIdentifierException.class);
    }

    @Test
    void currencyCode_rejectsWrongLength() {
        assertThatThrownBy(() -> CurrencyCode.of("MX")).isInstanceOf(InvalidIdentifierException.class);
        assertThatThrownBy(() -> CurrencyCode.of("MXNN")).isInstanceOf(InvalidIdentifierException.class);
    }

    @Test
    void currencyCode_rejectsNull() {
        assertThatThrownBy(() -> CurrencyCode.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void money_hasScaleTwo() {
        Money money = Money.of("15000.5", MXN);
        assertThat(money.amount()).isEqualByComparingTo("15000.50");
        assertThat(money.amount().scale()).isEqualTo(2);
        assertThat(money.amount().toPlainString()).isEqualTo("15000.50");
    }

    @Test
    void money_normalizesTrailingZeros_10_500_is_10_50() {
        assertThat(Money.of("10.500", MXN)).isEqualTo(Money.of("10.50", MXN));
        assertThat(Money.of("10.500", MXN).amount().toPlainString()).isEqualTo("10.50");
    }

    @Test
    void money_rejectsScaleAboveTwo_10_005() {
        assertThatThrownBy(() -> Money.of("10.005", MXN))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("10.005");
    }

    @Test
    void money_neverRoundsInput() {
        // Neither half-up (10.01) nor half-even (10.00): the amount is refused, not corrected (D11).
        assertThatThrownBy(() -> new Money(new BigDecimal("10.005"), MXN))
                .isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> new Money(new BigDecimal("0.001"), MXN))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void money_precisionUpTo19_17IntegerDigits_isAccepted() {
        // numeric(19,2): 17 integer digits + 2 decimals (spec §6.1, §9).
        assertThat(Money.of("99999999999999999.99", MXN).amount().precision()).isEqualTo(19);
        assertThat(Money.of("-99999999999999999.99", MXN).isNegative()).isTrue();
    }

    @Test
    void money_rejectsPrecisionAbove19_18IntegerDigits() {
        assertThatThrownBy(() -> Money.of("100000000000000000.00", MXN))
                .isInstanceOf(InvalidAmountException.class)
                .isInstanceOf(DomainInvariantException.class);
        assertThatThrownBy(() -> Money.of("-100000000000000000", MXN)).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void money_rejectsHugeExponents_withoutExpandingThem() {
        // Checked before setScale: expanding 1E+999999999 or 1E-999999999 would hang or exhaust memory.
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertThatThrownBy(() -> Money.of("1E+999999999", MXN)).isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> Money.of("1E-999999999", MXN)).isInstanceOf(InvalidAmountException.class);
        });
    }

    @Test
    void money_sumAbovePrecision19_throws() {
        // A balance that would not fit numeric(19,2) fails in the domain, not as an SQL error.
        Money max = Money.of("99999999999999999.99", MXN);
        assertThatThrownBy(() -> max.plus(Money.of("0.01", MXN))).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void money_rejectsNull() {
        assertThatThrownBy(() -> new Money(null, MXN)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void money_allowsNegative_forCobreBalances() {
        Money money = Money.of("-880.25", USD);
        assertThat(money.isNegative()).isTrue();
        assertThat(money.amount().toPlainString()).isEqualTo("-880.25");
    }

    @Test
    void money_addsAndSubtractsSameCurrency() {
        assertThat(Money.of("200000.00", MXN).minus(Money.of("15000.50", MXN)))
                .isEqualTo(Money.of("184999.50", MXN));
        assertThat(Money.zero(USD).plus(Money.of("880.25", USD))).isEqualTo(Money.of("880.25", USD));
        assertThat(Money.of("880.25", USD).negate()).isEqualTo(Money.of("-880.25", USD));
    }

    @Test
    void money_addDifferentCurrency_throws() {
        assertThatThrownBy(() -> Money.of("1.00", MXN).plus(Money.of("1.00", USD)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> Money.of("1.00", MXN).minus(Money.of("1.00", USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void money_compareDifferentCurrency_throws() {
        assertThatThrownBy(() -> Money.of("1.00", MXN).compareTo(Money.of("1.00", USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void money_comparesSameCurrency() {
        assertThat(Money.of("100.00", MXN).compareTo(Money.of("150.00", MXN))).isNegative();
        assertThat(Money.of("100", MXN).compareTo(Money.of("100.00", MXN))).isZero();
        assertThat(Money.of("0.01", MXN).isPositive()).isTrue();
        assertThat(Money.zero(MXN).isPositive()).isFalse();
    }

    @Test
    void money_isImmutable() {
        Money original = Money.of("100.00", MXN);
        original.plus(Money.of("1.00", MXN));
        assertThat(original).isEqualTo(Money.of("100.00", MXN));
    }
}
