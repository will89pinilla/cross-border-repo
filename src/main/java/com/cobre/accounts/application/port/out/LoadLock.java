package com.cobre.accounts.application.port.out;

import java.util.Optional;

/**
 * Only one load runs at a time, also across instances (spec §7.3). The adapter is a PostgreSQL
 * advisory lock ({@code pg_try_advisory_lock}).
 */
public interface LoadLock {

    /** @return the lease, or empty at once if another load holds the lock: it never waits */
    Optional<Lease> tryAcquire();

    /** Held while the load runs; closing it releases the lock. */
    interface Lease extends AutoCloseable {

        @Override
        void close();
    }
}
