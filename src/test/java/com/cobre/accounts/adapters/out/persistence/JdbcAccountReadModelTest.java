package com.cobre.accounts.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.application.port.in.AccountBalance;
import com.cobre.accounts.application.port.in.EntryLinePage;
import com.cobre.accounts.application.port.in.EntryLinePage.Line;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.AccountType;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Direction;
import com.cobre.accounts.support.FreshDatabase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The read model of spec §8.1 and §8.2 against PostgreSQL 18 as {@code accounts_app}. Rows are
 * written directly, so each case is exact:
 *
 * <ul>
 *   <li>ACC_A (MXN, ACTIVE) and ACC_B (USD, BLOCKED) with their openings (100.00 and 5.00).
 *   <li>Two entries of 99999999999999999.99 MXN each on COBRE_FX_POSITION_MXN: its balance,
 *       199999999999999999.98, has 18 integer digits, above the cap of an input amount (T04 review,
 *       finding 3: a Cobre balance has no cap of its own, spec §9).
 *   <li>{@code B_evt} and {@code a_evt} at the same {@code posted_at} on the USD Cobre accounts, written
 *       in that order: {@code String.compareTo} puts "B" before "a"; a locale collation would not.
 *   <li>COBRE_FUNDING_EUR exists without lines.
 * </ul>
 *
 * Hand computed: COBRE_FUNDING_MXN = −100.00 − 2 × 99999999999999999.99 = −200000000000000099.98, so
 * MXN 100.00 + 199999999999999999.98 − 200000000000000099.98 = 0.00; USD 5.00 + 0.50 −
 * 5.50 = 0.00; EUR 0.00.
 */
class JdbcAccountReadModelTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final AccountId ACC_A = AccountId.of("ACC_A");
    private static final AccountId ACC_B = AccountId.of("ACC_B");
    private static final Instant OPENING = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant TEN = Instant.parse("2025-09-09T10:00:00Z");
    private static final String HUGE = "99999999999999999.99";

    private JdbcClient jdbc;
    private JdbcAccountReadModel readModel;

    @BeforeEach
    void ledger() {
        jdbc = JdbcClient.create(FreshDatabase.create().appDataSource());
        readModel = new JdbcAccountReadModel(jdbc);
        account("ACC_A", "MXN", "CLIENT", "ACTIVE", "100.00");
        account("ACC_B", "USD", "CLIENT", "BLOCKED", "5.00");
        for (String currency : List.of("MXN", "USD", "EUR")) {
            account("COBRE_FX_POSITION_" + currency, currency, "COBRE_POSITION", "ACTIVE", null);
            account("COBRE_FUNDING_" + currency, currency, "COBRE_FUNDING", "ACTIVE", null);
        }
        entry("opening_ACC_A", OPENING, "MXN", "COBRE_FUNDING_MXN", "ACC_A", "100.00");
        entry("opening_ACC_B", OPENING, "USD", "COBRE_FUNDING_USD", "ACC_B", "5.00");
        entry("huge_1", TEN, "MXN", "COBRE_FUNDING_MXN", "COBRE_FX_POSITION_MXN", HUGE);
        entry("huge_2", TEN, "MXN", "COBRE_FUNDING_MXN", "COBRE_FX_POSITION_MXN", HUGE);
        entry("B_evt", TEN, "USD", "COBRE_FX_POSITION_USD", "COBRE_FUNDING_USD", "0.50");
        entry("a_evt", TEN, "USD", "COBRE_FUNDING_USD", "COBRE_FX_POSITION_USD", "1.00");
    }

    @Test
    void AC14_balanceOfClientAccountsIsTheStoredOneWithTypeAndStatus() {
        assertThat(readModel.balanceOf(ACC_A)).contains(
                new AccountBalance(ACC_A, AccountType.CLIENT, AccountStatus.ACTIVE, MXN, new BigDecimal("100.00")));
        assertThat(readModel.balanceOf(ACC_B)).contains(
                new AccountBalance(ACC_B, AccountType.CLIENT, AccountStatus.BLOCKED, USD, new BigDecimal("5.00")));
    }

    @Test
    void AC14_cobreBalanceIsTheSumOfItsLinesWithoutTheCapOfAnInputAmount() {
        assertThat(readModel.balanceOf(AccountId.fxPosition(MXN))).contains(new AccountBalance(
                AccountId.fxPosition(MXN), AccountType.COBRE_POSITION, AccountStatus.ACTIVE, MXN,
                new BigDecimal("199999999999999999.98")));
        assertThat(readModel.balanceOf(AccountId.funding(MXN)).orElseThrow().balance())
                .isEqualTo(new BigDecimal("-200000000000000099.98"));
    }

    @Test
    void AC14_cobreAccountWithoutLinesHasBalanceZeroWithScaleTwo() {
        assertThat(readModel.balanceOf(AccountId.funding(EUR)).orElseThrow().balance()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void AC14_unknownAccountHasNoBalanceAndNoLines() {
        assertThat(readModel.balanceOf(AccountId.of("ACC_NONE"))).isEmpty();
        assertThat(readModel.linesOf(AccountId.of("ACC_NONE"), 10, 0)).isEmpty();
    }

    @Test
    void balancesOfReturnsOnlyTheAccountsThatExist() {
        assertThat(readModel.balancesOf(List.of(ACC_A, AccountId.fxPosition(USD), AccountId.of("ACC_NONE"))))
                .containsOnly(Map.entry(ACC_A, new BigDecimal("100.00")),
                        Map.entry(AccountId.fxPosition(USD), new BigDecimal("0.50")));
        assertThat(readModel.balancesOf(List.of())).isEmpty();
    }

    @Test
    void AC12_invariantHasEveryCurrencyInOrderAndIsZero() {
        assertThat(readModel.invariant()).containsExactly(Map.entry(EUR, new BigDecimal("0.00")),
                Map.entry(MXN, new BigDecimal("0.00")), Map.entry(USD, new BigDecimal("0.00")));
    }

    @Test
    void AC14_linesAreOrderedByPostedAtThenSourceEventIdInJavaOrderThenLineNo() {
        EntryLinePage page = readModel.linesOf(AccountId.funding(USD), 10, 0).orElseThrow();

        assertThat(page.accountId()).isEqualTo(AccountId.funding(USD));
        assertThat(page.currency()).isEqualTo(USD);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.limit()).isEqualTo(10);
        assertThat(page.offset()).isZero();
        // The opening goes first by date although "opening_" sorts after "B_evt" and "a_evt".
        assertThat(page.lines()).containsExactly(
                new Line("opening_ACC_B", 1, Direction.DEBIT, new BigDecimal("5.00"), OPENING),
                new Line("B_evt", 2, Direction.CREDIT, new BigDecimal("0.50"), TEN),
                new Line("a_evt", 1, Direction.DEBIT, new BigDecimal("1.00"), TEN));
    }

    @Test
    void AC14_aPageHasTheTotalOfAllLines() {
        assertThat(readModel.linesOf(AccountId.funding(USD), 1, 1).orElseThrow().lines())
                .extracting(Line::sourceEventId).containsExactly("B_evt");
        EntryLinePage past = readModel.linesOf(AccountId.funding(USD), 5, 10).orElseThrow();
        assertThat(past.total()).isEqualTo(3);
        assertThat(past.lines()).isEmpty();
        // An account with no lines still exists: an empty page, not "not found".
        assertThat(readModel.linesOf(AccountId.funding(EUR), 10, 0).orElseThrow().total()).isZero();
    }

    private void account(String id, String currency, String type, String status, String balance) {
        jdbc.sql("INSERT INTO account (account_id, currency, type, status, balance) VALUES (?, ?, ?, ?, ?)")
                .params(id, currency, type, status, balance == null ? null : new BigDecimal(balance))
                .update();
    }

    /** Line 1 debits {@code debited}, line 2 credits {@code credited}. */
    private void entry(String sourceEventId, Instant postedAt, String currency, String debited, String credited,
            String amount) {
        UUID entryId = UUID.randomUUID();
        Timestamp at = Timestamp.from(postedAt);
        jdbc.sql("INSERT INTO journal_entry (entry_id, source_event_id, posted_at) VALUES (?, ?, ?)")
                .params(entryId, sourceEventId, at).update();
        String line = "INSERT INTO entry_line (entry_id, line_no, account_id, direction, amount, currency, posted_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        jdbc.sql(line).params(entryId, 1, debited, "DEBIT", new BigDecimal(amount), currency, at).update();
        jdbc.sql(line).params(entryId, 2, credited, "CREDIT", new BigDecimal(amount), currency, at).update();
    }
}
