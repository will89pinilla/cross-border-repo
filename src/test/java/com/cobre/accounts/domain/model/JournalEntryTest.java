package com.cobre.accounts.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Invariants of {@code JournalEntry} and {@code EntryLine} (spec §3.4). */
class JournalEntryTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Instant AT = Instant.parse("2025-09-09T15:32:10Z");
    private static final EntryId ID = new EntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final String EVENT = "cbmm_20250909_000123";

    private static final Account ORIGIN =
            Account.client(AccountId.of("ACC123456789"), MXN, AccountStatus.ACTIVE, Money.of("200000.00", MXN));
    private static final Account DESTINATION =
            Account.client(AccountId.of("ACC987654321"), USD, AccountStatus.ACTIVE, Money.zero(USD));
    private static final Account POSITION_MXN = Account.fxPosition(MXN, AccountStatus.ACTIVE);
    private static final Account POSITION_USD = Account.fxPosition(USD, AccountStatus.ACTIVE);

    /** The four lines of spec §7.1 for the case example. */
    private static List<EntryLine> caseLines() {
        return List.of(
                EntryLine.of(ORIGIN, Direction.DEBIT, Money.of("15000.50", MXN), AT),
                EntryLine.of(POSITION_MXN, Direction.CREDIT, Money.of("15000.50", MXN), AT),
                EntryLine.of(POSITION_USD, Direction.DEBIT, Money.of("880.25", USD), AT),
                EntryLine.of(DESTINATION, Direction.CREDIT, Money.of("880.25", USD), AT));
    }

    @Test
    void entry_withBalancedLinesPerCurrency_isCreated() {
        JournalEntry entry = JournalEntry.of(ID, EVENT, AT, caseLines());
        assertThat(entry.id()).isEqualTo(ID);
        assertThat(entry.sourceEventId()).isEqualTo(EVENT);
        assertThat(entry.postedAt()).isEqualTo(AT);
        assertThat(entry.lines()).hasSize(4);
    }

    @Test
    void atLeastTwoLines() {
        List<EntryLine> one = List.of(EntryLine.of(ORIGIN, Direction.DEBIT, Money.of("1.00", MXN), AT));
        assertThatThrownBy(() -> JournalEntry.of(ID, EVENT, AT, one)).isInstanceOf(UnbalancedEntryException.class);
        assertThatThrownBy(() -> JournalEntry.of(ID, EVENT, AT, List.of()))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void debitsEqualCreditsPerCurrency() {
        List<EntryLine> unbalanced = List.of(
                EntryLine.of(ORIGIN, Direction.DEBIT, Money.of("15000.50", MXN), AT),
                EntryLine.of(POSITION_MXN, Direction.CREDIT, Money.of("15000.49", MXN), AT));
        assertThatThrownBy(() -> JournalEntry.of(ID, EVENT, AT, unbalanced))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void debitsEqualCreditsPerCurrency_notAcrossCurrencies() {
        // Same numeric total across currencies does not balance the entry: MXN and USD each need 0.
        List<EntryLine> crossed = List.of(
                EntryLine.of(ORIGIN, Direction.DEBIT, Money.of("10.00", MXN), AT),
                EntryLine.of(DESTINATION, Direction.CREDIT, Money.of("10.00", USD), AT));
        assertThatThrownBy(() -> JournalEntry.of(ID, EVENT, AT, crossed))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void amountsPositive() {
        assertThatThrownBy(() -> EntryLine.of(ORIGIN, Direction.DEBIT, Money.zero(MXN), AT))
                .isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> EntryLine.of(ORIGIN, Direction.CREDIT, Money.of("-1.00", MXN), AT))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void lineCurrencyMatchesAccount() {
        assertThatThrownBy(() -> EntryLine.of(ORIGIN, Direction.DEBIT, Money.of("1.00", USD), AT))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void line_signedAmount_creditPositiveDebitNegative() {
        assertThat(EntryLine.of(ORIGIN, Direction.DEBIT, Money.of("5.00", MXN), AT).signedAmount())
                .isEqualTo(Money.of("-5.00", MXN));
        assertThat(EntryLine.of(ORIGIN, Direction.CREDIT, Money.of("5.00", MXN), AT).signedAmount())
                .isEqualTo(Money.of("5.00", MXN));
    }

    @Test
    void sourceEventIdRequired() {
        assertThatThrownBy(() -> JournalEntry.of(ID, " ", AT, caseLines()))
                .isInstanceOf(InvalidIdentifierException.class);
    }

    @Test
    void linesAreImmutable() {
        List<EntryLine> lines = new ArrayList<>(caseLines());
        JournalEntry entry = JournalEntry.of(ID, EVENT, AT, lines);
        lines.clear();
        assertThat(entry.lines()).hasSize(4);
        assertThatThrownBy(() -> entry.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
