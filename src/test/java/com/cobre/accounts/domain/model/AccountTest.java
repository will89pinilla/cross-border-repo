package com.cobre.accounts.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Invariants of {@code Account} (spec §3.4, D10). */
class AccountTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final AccountId ACC_MXN = AccountId.of("ACC123456789");

    private static Account client(String balance, AccountStatus status) {
        return Account.client(ACC_MXN, MXN, status, Money.of(balance, MXN));
    }

    @Test
    void accountId_rejectsBlank() {
        assertThatThrownBy(() -> AccountId.of(" ")).isInstanceOf(InvalidIdentifierException.class);
        assertThatThrownBy(() -> AccountId.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void accountId_cobreIdsFollowGlossaryConvention() {
        assertThat(AccountId.fxPosition(MXN)).isEqualTo(AccountId.of("COBRE_FX_POSITION_MXN"));
        assertThat(AccountId.funding(USD)).isEqualTo(AccountId.of("COBRE_FUNDING_USD"));
        assertThat(AccountId.of("COBRE_ANYTHING").isCobreReserved()).isTrue();
        assertThat(ACC_MXN.isCobreReserved()).isFalse();
    }

    /** D12 lock order = code point order = byte order of UTF-8, the order of COLLATE "C". */
    @Test
    void accountId_ordersByCodePoint() {
        AccountId halfwidth = AccountId.of("X｡");     // U+FF61
        AccountId emoji = AccountId.of("X😀");   // U+1F600, a surrogate pair in UTF-16
        assertThat(halfwidth.compareTo(emoji)).isNegative();
        assertThat(emoji.compareTo(halfwidth)).isPositive();
        assertThat(AccountId.of("ACC_c").compareTo(AccountId.of("Acc_a"))).isNegative();
        assertThat(AccountId.of("acc").compareTo(AccountId.of("acc_b"))).isNegative();
        assertThat(AccountId.of("acc").compareTo(AccountId.of("acc"))).isZero();
    }

    @Test
    void client_debitAndCredit_followBalanceConvention() {
        // balance = credits - debits (spec §2)
        Account account = client("200000.00", AccountStatus.ACTIVE);
        assertThat(account.debit(Money.of("15000.50", MXN)).balance()).contains(Money.of("184999.50", MXN));
        assertThat(account.credit(Money.of("0.50", MXN)).balance()).contains(Money.of("200000.50", MXN));
    }

    @Test
    void client_neverNegative_onDebit() {
        Account account = client("100.00", AccountStatus.ACTIVE);
        assertThatThrownBy(() -> account.debit(Money.of("150.00", MXN)))
                .isInstanceOf(NegativeClientBalanceException.class);
    }

    @Test
    void client_debitToExactlyZero_isAllowed() {
        assertThat(client("100.00", AccountStatus.ACTIVE).debit(Money.of("100.00", MXN)).balance())
                .contains(Money.zero(MXN));
    }

    @Test
    void client_neverNegative_onCreation() {
        assertThatThrownBy(() -> client("-0.01", AccountStatus.ACTIVE))
                .isInstanceOf(NegativeClientBalanceException.class);
    }

    @Test
    void client_balanceCurrencyMustMatchAccount() {
        assertThatThrownBy(() -> Account.client(ACC_MXN, MXN, AccountStatus.ACTIVE, Money.zero(USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void client_cannotUseCobreReservedId() {
        assertThatThrownBy(() -> Account.client(
                        AccountId.of("COBRE_FX_POSITION_MXN"), MXN, AccountStatus.ACTIVE, Money.zero(MXN)))
                .isInstanceOf(InvalidIdentifierException.class);
    }

    @Test
    void onlyActiveCanMove_blocked() {
        Account blocked = client("100.00", AccountStatus.BLOCKED);
        assertThatThrownBy(() -> blocked.debit(Money.of("1.00", MXN))).isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> blocked.credit(Money.of("1.00", MXN))).isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void onlyActiveCanMove_closed() {
        Account closed = client("100.00", AccountStatus.CLOSED);
        assertThatThrownBy(() -> closed.debit(Money.of("1.00", MXN))).isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> closed.credit(Money.of("1.00", MXN))).isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void onlyActiveCanMove_cobreAccount() {
        Account blocked = Account.fxPosition(MXN, AccountStatus.BLOCKED);
        assertThatThrownBy(() -> blocked.credit(Money.of("1.00", MXN))).isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void movement_inOtherCurrency_throws() {
        Account account = client("100.00", AccountStatus.ACTIVE);
        assertThatThrownBy(() -> account.credit(Money.of("1.00", USD))).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> Account.fxPosition(MXN, AccountStatus.ACTIVE).debit(Money.of("1.00", USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void movement_mustBePositive() {
        Account account = client("100.00", AccountStatus.ACTIVE);
        assertThatThrownBy(() -> account.credit(Money.zero(MXN))).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> account.debit(Money.of("-1.00", MXN))).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void cobreAccount_hasNoStoredBalance_D10() {
        Account position = Account.fxPosition(USD, AccountStatus.ACTIVE);
        Account funding = Account.funding(MXN, AccountStatus.ACTIVE);
        assertThat(position.type()).isEqualTo(AccountType.COBRE_POSITION);
        assertThat(position.id()).isEqualTo(AccountId.of("COBRE_FX_POSITION_USD"));
        assertThat(funding.type()).isEqualTo(AccountType.COBRE_FUNDING);
        assertThat(funding.id()).isEqualTo(AccountId.of("COBRE_FUNDING_MXN"));
        assertThat(position.balance()).isEmpty();
        // Cobre accounts may go negative: a debit is accepted and nothing is stored.
        assertThat(position.debit(Money.of("880.25", USD)).balance()).isEmpty();
    }

    @Test
    void typeAndCurrencyAreFinal_movementReturnsNewAccount() {
        Account original = client("100.00", AccountStatus.ACTIVE);
        Account debited = original.debit(Money.of("40.00", MXN));
        assertThat(original.balance()).contains(Money.of("100.00", MXN));
        assertThat(debited.id()).isEqualTo(original.id());
        assertThat(debited.currency()).isEqualTo(original.currency());
        assertThat(debited.type()).isEqualTo(AccountType.CLIENT);
        assertThat(debited.status()).isEqualTo(AccountStatus.ACTIVE);
    }
}
