package com.cobre.accounts.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.cobre.accounts.application.port.out.DecisionAlreadyExistsException;
import com.cobre.accounts.application.port.out.RetriesExhaustedException;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.model.SettlementDecision;
import com.cobre.accounts.support.PostgresIntegrationTest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spec §7.3: one transaction per call; 40P01 and 40001 are attempted at most 3 times, waiting 50 ms
 * and 100 ms plus a uniform jitter of 0-50 %; no other error is retried. The errors are raised by
 * PostgreSQL itself, and waits are recorded by a fake sleeper instead of slept.
 */
class JdbcUnitOfWorkTest extends PostgresIntegrationTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final List<Duration> waits = new CopyOnWriteArrayList<>();
    private JdbcAccountRepository accounts;
    private JdbcUnitOfWork unitOfWork;
    private String suffix;

    @BeforeEach
    void setUp() {
        accounts = new JdbcAccountRepository(jdbc);
        unitOfWork = unitOfWorkWithJitter(0.0);
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    private JdbcUnitOfWork unitOfWorkWithJitter(double jitter) {
        return new JdbcUnitOfWork(new TransactionTemplate(transactionManager), waits::add, () -> jitter);
    }

    private Account account(String name) {
        return Account.client(AccountId.of(name + "_" + suffix), MXN, AccountStatus.ACTIVE, Money.of("1.00", MXN));
    }

    private void raise(String sqlState) {
        jdbc.sql("DO $$ BEGIN RAISE EXCEPTION 'simulated' USING ERRCODE = '" + sqlState + "'; END $$").update();
    }

    @Test
    void commitsTheWorkAndReturnsItsResult() {
        Account account = account("ACC_COMMIT");

        String result = unitOfWork.inTransaction(() -> {
            accounts.insertIfAbsent(account);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(accounts.findById(account.id())).contains(account);
    }

    @Test
    void anExceptionRollsBackEverythingAndIsRethrownAsIs() {
        Account account = account("ACC_ROLLBACK");
        IllegalStateException failure = new IllegalStateException("business failure");

        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> {
            accounts.insertIfAbsent(account);
            throw failure;
        })).isSameAs(failure);

        assertThat(accounts.findById(account.id())).isEmpty();
        assertThat(waits).isEmpty();
    }

    @ParameterizedTest(name = "retries SQLSTATE {0}")
    @ValueSource(strings = {"40001", "40P01"})
    void retriesDeadlockAndSerializationFailureAndRollsBackTheFailedAttempt(String sqlState) {
        AtomicInteger attempts = new AtomicInteger();
        Account failedAttempt = account("ACC_FAILED");
        Account successfulAttempt = account("ACC_OK");

        unitOfWork.inTransaction(() -> {
            if (attempts.incrementAndGet() == 1) {
                accounts.insertIfAbsent(failedAttempt);
                raise(sqlState);
            }
            accounts.insertIfAbsent(successfulAttempt);
        });

        assertThat(attempts).hasValue(2);
        assertThat(waits).containsExactly(Duration.ofMillis(50));
        assertThat(accounts.findById(failedAttempt.id())).isEmpty();
        assertThat(accounts.findById(successfulAttempt.id())).isPresent();
    }

    @ParameterizedTest(name = "gives up on SQLSTATE {0}")
    @ValueSource(strings = {"40001", "40P01"})
    void givesUpAfterThreeAttemptsInTotal(String sqlState) {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> {
            attempts.incrementAndGet();
            raise(sqlState);
        }))
                .isInstanceOf(RetriesExhaustedException.class)
                .satisfies(e -> {
                    assertThat(((RetriesExhaustedException) e).attempts()).isEqualTo(3);
                    assertThat(sqlStateOf(e)).isEqualTo(sqlState);
                });

        assertThat(attempts).hasValue(3);
        assertThat(waits).containsExactly(Duration.ofMillis(50), Duration.ofMillis(100));
    }

    /** Wait = base + jitter x 50 % of base, jitter uniform in [0, 1). */
    @ParameterizedTest(name = "jitter {0}: waits {1} and {2} microseconds")
    @CsvSource({"0.0, 50000, 100000", "0.5, 62500, 125000", "0.999, 74975, 149950"})
    void jitterAddsUpToHalfOfEachWait(double jitter, long firstMicros, long secondMicros) {
        JdbcUnitOfWork jittered = unitOfWorkWithJitter(jitter);

        assertThatThrownBy(() -> jittered.inTransaction(() -> raise("40001")))
                .isInstanceOf(RetriesExhaustedException.class);

        assertThat(waits).containsExactly(Duration.ofNanos(firstMicros * 1_000), Duration.ofNanos(secondMicros * 1_000));
        assertThat(waits.get(0)).isLessThan(Duration.ofMillis(75));
        assertThat(waits.get(1)).isLessThan(Duration.ofMillis(150));
    }

    /** User adjustment for T04: 23505 rolls back and is never retried; PostSettlement reports a duplicate (T05). */
    @Test
    void decisionAlreadyExistsRollsBackAndIsNotRetried() {
        JdbcSettlementDecisionRepository decisions = new JdbcSettlementDecisionRepository(jdbc);
        String eventId = "evt_" + UUID.randomUUID();
        decisions.insert(SettlementDecision.rejected(eventId, RejectionCode.INSUFFICIENT_FUNDS, null), "h", UUID.randomUUID());
        Account sameTransaction = account("ACC_DUP");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> {
            attempts.incrementAndGet();
            accounts.insertIfAbsent(sameTransaction);
            decisions.insert(SettlementDecision.rejected(eventId, RejectionCode.ACCOUNT_INACTIVE, "ORIGIN"),
                    "h", UUID.randomUUID());
        }))
                .isInstanceOf(DecisionAlreadyExistsException.class)
                .satisfies(e -> assertThat(sqlStateOf(e)).isEqualTo("23505"));

        assertThat(attempts).hasValue(1);
        assertThat(waits).isEmpty();
        assertThat(accounts.findById(sameTransaction.id())).isEmpty();
        assertThat(decisions.find(eventId))
                .contains(SettlementDecision.rejected(eventId, RejectionCode.INSUFFICIENT_FUNDS, null));
    }

    @ParameterizedTest(name = "does not retry SQLSTATE {0}")
    @ValueSource(strings = {"23505", "42501", "55P03", "57014", "40003"})
    void otherDatabaseErrorsAreNotRetried(String sqlState) {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> {
            attempts.incrementAndGet();
            raise(sqlState);
        })).satisfies(e -> {
            assertThat(e).isNotInstanceOf(RetriesExhaustedException.class);
            assertThat(sqlStateOf(e)).isEqualTo(sqlState);
        });

        assertThat(attempts).hasValue(1);
        assertThat(waits).isEmpty();
    }

    /** Review finding 7: a cause chain with a cycle longer than 1 must not loop forever. */
    @Test
    void causeChainWithACycleIsNotRetryableAndTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);

        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(2), () -> JdbcUnitOfWork.isRetryable(a))).isFalse();
        assertThat(JdbcUnitOfWork.isRetryable(new RuntimeException(new SQLException("x", "40P01")))).isTrue();
    }

    @Test
    void nestedTransactionsAreRefused() {
        assertThatThrownBy(() -> unitOfWork.inTransaction(() -> unitOfWork.inTransaction(() -> "inner")))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A real deadlock: two transactions lock A and B in opposite order. PostgreSQL aborts one with
     * 40P01; it is retried once the other has committed, and both end up applied.
     */
    @Test
    void aRealDeadlockIsRetriedAndBothTransactionsCommit() throws Exception {
        Account a = account("ACC_DL_A");
        Account b = account("ACC_DL_B");
        accounts.insertIfAbsent(a);
        accounts.insertIfAbsent(b);
        CyclicBarrier bothHoldTheirFirstLock = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transfer(a, b, bothHoldTheirFirstLock, attempts));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> transfer(b, a, bothHoldTheirFirstLock, attempts));
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        assertThat(attempts).hasValue(3);
        assertThat(waits).containsExactly(Duration.ofMillis(50));
        assertThat(accounts.balanceOf(a.id())).contains(Money.of("1.00", MXN));
        assertThat(accounts.balanceOf(b.id())).contains(Money.of("1.00", MXN));
    }

    /** Moves 0.50 from one account to the other, deliberately locking out of D12 order. */
    private void transfer(Account from, Account to, CyclicBarrier barrier, AtomicInteger attempts) {
        AtomicInteger mine = new AtomicInteger();
        unitOfWork.inTransaction(() -> {
            attempts.incrementAndGet();
            Account source = lockOne(from);
            if (mine.incrementAndGet() == 1) {
                awaitBarrier(barrier);
            }
            Account target = lockOne(to);
            accounts.updateBalance(source.debit(Money.of("0.50", MXN)));
            accounts.updateBalance(target.credit(Money.of("0.50", MXN)));
        });
    }

    private Account lockOne(Account account) {
        return accounts.lockClientAccounts(List.of(account.id())).getFirst();
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("barrier", e);
        }
    }

    private static String sqlStateOf(Throwable e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        return root instanceof SQLException sql ? sql.getSQLState() : null;
    }
}
