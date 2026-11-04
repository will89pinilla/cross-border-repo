package com.cobre.accounts.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Direction;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.JournalEntry;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.support.PostgresIntegrationTest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Accounts on PostgreSQL 18 as accounts_app: insert, D12 locks (M2), CLIENT balance and Cobre SUM (D10). */
class JdbcAccountRepositoryTest extends PostgresIntegrationTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    private JdbcAccountRepository accounts;
    private TransactionTemplate tx;
    private String suffix;

    @BeforeEach
    void setUp() {
        accounts = new JdbcAccountRepository(jdbc);
        tx = new TransactionTemplate(transactionManager);
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    private Account client(String id, String balance) {
        return Account.client(AccountId.of(id + "_" + suffix), MXN, AccountStatus.ACTIVE, Money.of(balance, MXN));
    }

    @Test
    void insertedClientAccountIsReadBack() {
        Account blocked = Account.client(
                AccountId.of("ACC_B_" + suffix), MXN, AccountStatus.BLOCKED, Money.of("12.34", MXN));

        assertThat(accounts.insertIfAbsent(blocked)).isTrue();

        assertThat(accounts.findById(blocked.id())).contains(blocked);
        assertThat(accounts.findById(AccountId.of("MISSING_" + suffix))).isEmpty();
    }

    @Test
    void insertIfAbsentLeavesAnExistingAccountUntouched() {
        Account original = client("ACC", "10.00");
        accounts.insertIfAbsent(original);

        assertThat(accounts.insertIfAbsent(client("ACC", "99.00"))).isFalse();

        assertThat(accounts.findById(original.id())).contains(original);
    }

    @Test
    void cobreAccountsAreReadWithoutStoredBalance() {
        assertThat(accounts.findById(AccountId.fxPosition(MXN))).contains(Account.fxPosition(MXN, AccountStatus.ACTIVE));
        assertThat(accounts.findById(AccountId.funding(MXN))).contains(Account.funding(MXN, AccountStatus.ACTIVE));
    }

    /** M2: Java order (String.compareTo) and SQL order (COLLATE "C") agree, also with mixed case. */
    @Test
    void lockClientAccountsReturnsOnlyClientsInAscendingAccountIdOrder() {
        List<Account> created = List.of(client("acc_b", "1.00"), client("ACC_c", "2.00"),
                client("Acc_a", "3.00"), client("_acc", "4.00"), client("acc_B", "5.00"));
        created.forEach(accounts::insertIfAbsent);
        List<AccountId> requested = new java.util.ArrayList<>(created.stream().map(Account::id).toList());
        requested.add(AccountId.fxPosition(MXN));
        requested.add(AccountId.of("MISSING_" + suffix));

        List<Account> locked = tx.execute(status -> accounts.lockClientAccounts(requested));

        List<AccountId> expectedOrder = created.stream().map(Account::id).sorted().toList();
        assertThat(locked).extracting(Account::id).containsExactlyElementsOf(expectedOrder);
        assertThat(expectedOrder).extracting(AccountId::value).containsExactly(
                "ACC_c_" + suffix, "Acc_a_" + suffix, "_acc_" + suffix, "acc_B_" + suffix, "acc_b_" + suffix);
        assertThat(locked).allSatisfy(account -> assertThat(account.isClient()).isTrue());
    }

    /**
     * Review finding 6: outside the BMP, UTF-16 order (String.compareTo) and byte order (COLLATE "C"
     * on UTF-8) differ; AccountId orders by code point, which is the byte order.
     */
    @Test
    void lockOrderMatchesCollateCOutsideTheBasicMultilingualPlane() {
        Account halfwidth = client("X｡", "1.00");      // U+FF61, UTF-8 EF BD A1
        Account emoji = client("X😀", "1.00");    // U+1F600, UTF-8 F0 9F 98 80
        accounts.insertIfAbsent(emoji);
        accounts.insertIfAbsent(halfwidth);
        List<AccountId> ids = List.of(emoji.id(), halfwidth.id());

        List<Account> locked = tx.execute(status -> accounts.lockClientAccounts(ids));
        List<String> collateC = jdbc.sql("SELECT account_id FROM account WHERE account_id = ANY (?) ORDER BY account_id COLLATE \"C\"")
                .params((Object) ids.stream().map(AccountId::value).toArray(String[]::new))
                .query(String.class).list();

        assertThat(locked).extracting(Account::id).containsExactly(halfwidth.id(), emoji.id());
        assertThat(collateC).containsExactly(halfwidth.id().value(), emoji.id().value());
        assertThat(ids.stream().sorted().toList()).containsExactly(halfwidth.id(), emoji.id());
    }

    @Test
    void lockClientAccountsHoldsARowLockUntilCommit() throws Exception {
        Account account = client("ACC_LOCK", "1.00");
        accounts.insertIfAbsent(account);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            accounts.lockClientAccounts(List.of(account.id()));
            locked.countDown();
            await(release);
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL lock_timeout = '200ms'").update();
                accounts.lockClientAccounts(List.of(account.id()));
            })).satisfies(e -> assertThat(sqlState(e)).isEqualTo(LOCK_NOT_AVAILABLE));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        List<Account> afterRelease = tx.execute(status -> accounts.lockClientAccounts(List.of(account.id())));
        assertThat(afterRelease).containsExactly(account);
    }

    @Test
    void lockClientAccountsWithNoIdsLocksNothing() {
        List<Account> locked = tx.execute(status -> accounts.lockClientAccounts(List.of()));
        assertThat(locked).isEmpty();
    }

    @Test
    void updateBalanceStoresTheNewClientBalance() {
        Account account = client("ACC_UPD", "100.00");
        accounts.insertIfAbsent(account);

        accounts.updateBalance(account.debit(Money.of("30.25", MXN)));

        assertThat(accounts.balanceOf(account.id())).contains(Money.of("69.75", MXN));
    }

    @Test
    void updateBalanceRejectsCobreAccountsAndUnknownAccounts() {
        assertThatThrownBy(() -> accounts.updateBalance(Account.fxPosition(MXN, AccountStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.updateBalance(client("ACC_NOPE", "1.00")))
                .isInstanceOf(IllegalStateException.class);
    }

    /** D10: a Cobre balance is SUM(CREDIT) - SUM(DEBIT) of its lines, never a stored row. */
    @Test
    void cobreBalanceIsTheSumOfItsLines() {
        Money positionBefore = accounts.balanceOf(AccountId.fxPosition(MXN)).orElseThrow();
        Money fundingBefore = accounts.balanceOf(AccountId.funding(MXN)).orElseThrow();
        Account position = Account.fxPosition(MXN, AccountStatus.ACTIVE);
        Account funding = Account.funding(MXN, AccountStatus.ACTIVE);
        Instant at = Instant.parse("2025-09-09T15:32:10Z");
        JournalEntry entry = JournalEntry.of(new EntryId(UUID.randomUUID()), "evt_sum_" + suffix, at, List.of(
                EntryLine.of(position, Direction.CREDIT, Money.of("0.10", MXN), at),
                EntryLine.of(position, Direction.CREDIT, Money.of("0.20", MXN), at),
                EntryLine.of(position, Direction.DEBIT, Money.of("0.05", MXN), at),
                EntryLine.of(funding, Direction.DEBIT, Money.of("0.25", MXN), at)));

        new JdbcJournalRepository(jdbc, jdbcTemplate).insert(entry);

        assertThat(accounts.balanceOf(position.id())).contains(positionBefore.plus(Money.of("0.25", MXN)));
        assertThat(accounts.balanceOf(funding.id())).contains(fundingBefore.minus(Money.of("0.25", MXN)));
        assertThat(accounts.balanceOf(AccountId.of("MISSING_" + suffix))).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String sqlState(Throwable e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        return root instanceof SQLException sql ? sql.getSQLState() : null;
    }
}
