package com.cobre.accounts.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.application.port.out.LoadLock;
import com.cobre.accounts.support.FreshDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One load at a time with {@code pg_try_advisory_lock}, also across instances (spec §7.3, AC16).
 * Each test has its own database: advisory locks are per database.
 */
class PostgresLoadLockTest {

    private final FreshDatabase db = FreshDatabase.create();
    private final DataSource dataSource = db.appDataSource();

    private long advisoryLocksHeld() {
        return JdbcClient.create(dataSource)
                .sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted"
                        + " AND database = (SELECT oid FROM pg_database WHERE datname = current_database())")
                .query(Long.class).single();
    }

    @Test
    void AC16_aSecondLoadWhileOneHoldsTheLockIsRefused() {
        LoadLock lock = new PostgresLoadLock(dataSource);

        try (LoadLock.Lease first = lock.tryAcquire().orElseThrow()) {
            assertThat(lock.tryAcquire()).isEmpty();
            assertThat(advisoryLocksHeld()).isEqualTo(1);
        }
    }

    /** Two instances of the service share the database, so they share the lock. */
    @Test
    void AC16_anotherInstanceCannotStartALoadWhileOneRuns() {
        LoadLock instanceA = new PostgresLoadLock(dataSource);
        LoadLock instanceB = new PostgresLoadLock(db.appDataSource());

        try (LoadLock.Lease running = instanceA.tryAcquire().orElseThrow()) {
            assertThat(instanceB.tryAcquire()).isEmpty();
        }
        Optional<LoadLock.Lease> next = instanceB.tryAcquire();
        assertThat(next).isPresent();
        next.get().close();
    }

    @Test
    void AC16_aLockHeldByAnotherSessionRefusesTheLoad() throws SQLException {
        try (Connection other = dataSource.getConnection(); Statement sql = other.createStatement()) {
            sql.execute("SELECT pg_advisory_lock(" + PostgresLoadLock.LOAD_LOCK_KEY + ")");

            assertThat(new PostgresLoadLock(dataSource).tryAcquire()).isEmpty();
        }
        // The other session ended: its lock went with it.
        Optional<LoadLock.Lease> lease = new PostgresLoadLock(dataSource).tryAcquire();
        assertThat(lease).isPresent();
        lease.get().close();
    }

    @Test
    void closingTheLeaseReleasesTheLockForTheNextLoad() {
        LoadLock lock = new PostgresLoadLock(dataSource);
        lock.tryAcquire().orElseThrow().close();

        assertThat(advisoryLocksHeld()).isZero();
        Optional<LoadLock.Lease> again = lock.tryAcquire();
        assertThat(again).isPresent();
        again.get().close();
    }

    @Test
    void closingTwiceIsHarmless() {
        LoadLock.Lease lease = new PostgresLoadLock(dataSource).tryAcquire().orElseThrow();
        lease.close();
        lease.close();

        assertThat(advisoryLocksHeld()).isZero();
    }

    /**
     * An unlock that fails on a live connection: the lease does not throw (the load already ended,
     * its own exception must not be hidden) and aborts the connection, so PostgreSQL ends the
     * session and drops its lock instead of the pool keeping it.
     */
    @Test
    void aFailingUnlockAbortsTheConnectionAndDoesNotThrow() throws SQLException {
        UnlockFaults faults = new UnlockFaults(dataSource, true);
        LoadLock.Lease lease = new PostgresLoadLock(faults.dataSource()).tryAcquire().orElseThrow();

        lease.close();

        assertThat(faults.aborted).isTrue();
        assertThat(faults.physical.isClosed()).isTrue();
        assertTheLockIsFreeOnceTheSessionEnds();
    }

    /** An unlock that returns false (the session did not hold the lock) is also handled by aborting. */
    @Test
    void anUnlockReturningFalseAbortsTheConnectionAndDoesNotThrow() throws SQLException {
        UnlockFaults faults = new UnlockFaults(dataSource, false);
        LoadLock.Lease lease = new PostgresLoadLock(faults.dataSource()).tryAcquire().orElseThrow();

        lease.close();

        assertThat(faults.aborted).isTrue();
        assertThat(faults.physical.isClosed()).isTrue();
        assertTheLockIsFreeOnceTheSessionEnds();
    }

    /** A blocking lock with a timeout waits for the aborted backend to end, without sleeping. */
    private void assertTheLockIsFreeOnceTheSessionEnds() throws SQLException {
        try (Connection other = dataSource.getConnection(); Statement sql = other.createStatement()) {
            sql.execute("SET lock_timeout = '5s'");
            sql.execute("SELECT pg_advisory_lock(" + PostgresLoadLock.LOAD_LOCK_KEY + ")");
            sql.execute("SELECT pg_advisory_unlock(" + PostgresLoadLock.LOAD_LOCK_KEY + ")");
        }
    }

    /**
     * A DataSource whose connection breaks {@code pg_advisory_unlock}: it throws (the statement
     * fails, the connection stays alive) or, before running it, drops the lock so it returns false.
     */
    private static final class UnlockFaults {

        private final DataSource target;
        private final boolean throwOnUnlock;
        volatile Connection physical;
        volatile boolean aborted;

        UnlockFaults(DataSource target, boolean throwOnUnlock) {
            this.target = target;
            this.throwOnUnlock = throwOnUnlock;
        }

        DataSource dataSource() {
            return (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("getConnection")) {
                            return invoke(method, target, args);
                        }
                        physical = target.getConnection();
                        return connection(physical);
                    });
        }

        private Connection connection(Connection real) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("abort")) {
                            aborted = true;
                        }
                        if (method.getName().equals("prepareStatement") && ((String) args[0]).contains("unlock")) {
                            if (throwOnUnlock) {
                                throw new SQLException("canceling statement due to statement timeout", "57014");
                            }
                            try (Statement drop = real.createStatement()) {
                                drop.execute("SELECT pg_advisory_unlock_all()");
                            }
                        }
                        return invoke(method, real, args);
                    });
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
