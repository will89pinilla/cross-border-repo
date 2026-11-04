package com.cobre.accounts.adapters.out.persistence;

import com.cobre.accounts.application.port.out.RetriesExhaustedException;
import com.cobre.accounts.application.port.out.UnitOfWork;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link UnitOfWork} on {@link TransactionTemplate} with the retries of spec §7.3: at most 3
 * attempts for 40P01 (deadlock) and 40001 (serialization failure), waiting 50 ms then 100 ms plus a
 * uniform jitter of 0-50 % of each wait. Any other error, including 23505, is rethrown at once.
 */
public final class JdbcUnitOfWork implements UnitOfWork {

    static final int MAX_ATTEMPTS = 3;
    static final List<Duration> WAITS = List.of(Duration.ofMillis(50), Duration.ofMillis(100));
    private static final Set<String> RETRYABLE = Set.of("40P01", "40001");

    /** Waits between attempts; injected so tests record the waits instead of sleeping. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        static Sleeper threadSleep() {
            return Thread::sleep;
        }
    }

    private final TransactionTemplate transactions;
    private final Sleeper sleeper;
    private final DoubleSupplier jitter;

    /** @param jitter uniform in [0, 1) */
    public JdbcUnitOfWork(TransactionTemplate transactions, Sleeper sleeper, DoubleSupplier jitter) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("UnitOfWork cannot run inside another transaction");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new RetriesExhaustedException(attempt, e);
                }
                pause(WAITS.get(attempt - 1));
            }
        }
    }

    private void pause(Duration base) {
        long extra = Math.round(base.toNanos() * 0.5 * jitter.getAsDouble());
        try {
            sleeper.sleep(base.plusNanos(extra));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry the transaction", e);
        }
    }

    /** Looks for the SQLSTATE in the whole cause chain, whatever Spring translated it to. */
    static boolean isRetryable(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql && RETRYABLE.contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
