package com.cobre.accounts.application.port.out;

import java.util.function.Supplier;

/**
 * One database transaction, opened by the application (spec §11). A deadlock ({@code 40P01}) or a
 * serialization failure ({@code 40001}) is attempted at most 3 times in total, waiting 50 ms and
 * 100 ms plus a uniform jitter of 0 % to 50 % of each wait. No other error is retried (spec §7.3).
 *
 * <p>{@code work} may run more than once, so it must have no effects outside the transaction.
 */
public interface UnitOfWork {

    /**
     * Runs {@code work} in a new transaction and commits it; any exception rolls it back.
     *
     * @throws RetriesExhaustedException if the third attempt also fails with 40P01 or 40001
     * @throws IllegalStateException if called inside another transaction (a retry there would
     *     replay only part of the outer one)
     */
    <T> T inTransaction(Supplier<T> work);

    default void inTransaction(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
