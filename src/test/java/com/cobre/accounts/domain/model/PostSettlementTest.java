package com.cobre.accounts.domain.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@code PostSettlement} only exists for events that passed rules 1 and 2 (spec §7.2): building one
 * with invalid data is a programming error in the translator, not a business rejection.
 */
class PostSettlementTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Instant AT = Instant.parse("2025-09-09T15:32:10Z");
    private static final AccountId ORIGIN = AccountId.of("ACC123456789");
    private static final AccountId DESTINATION = AccountId.of("ACC987654321");

    @Test
    void amountsMustBePositive() {
        assertThatThrownBy(() -> new PostSettlement("e1", AT, ORIGIN, Money.zero(MXN), DESTINATION, Money.of("1.00", USD)))
                .isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> new PostSettlement("e1", AT, ORIGIN, Money.of("1.00", MXN), DESTINATION, Money.of("-1.00", USD)))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void legsMustUseDifferentCurrencies() {
        assertThatThrownBy(() -> new PostSettlement("e1", AT, ORIGIN, Money.of("1.00", MXN), DESTINATION, Money.of("1.00", MXN)))
                .isInstanceOf(SameCurrencyLegsException.class);
    }

    @Test
    void sourceEventIdRequired() {
        assertThatThrownBy(() -> new PostSettlement(" ", AT, ORIGIN, Money.of("1.00", MXN), DESTINATION, Money.of("1.00", USD)))
                .isInstanceOf(InvalidIdentifierException.class);
        assertThatThrownBy(() -> new PostSettlement("e1", null, ORIGIN, Money.of("1.00", MXN), DESTINATION, Money.of("1.00", USD)))
                .isInstanceOf(NullPointerException.class);
    }
}
