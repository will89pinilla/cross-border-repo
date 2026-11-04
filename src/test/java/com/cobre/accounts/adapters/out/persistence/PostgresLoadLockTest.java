package com.cobre.accounts.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.application.port.out.LoadLock;
import com.cobre.accounts.support.FreshDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
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

    // --- Faults, through a real Hikari pool of one connection, as in the service ----------------

    /**
     * An unlock that fails on a live connection: the lease does not throw (the load already ended,
     * its own exception must not be hidden) and aborts the connection. The pool evicts it and lends
     * another session, and PostgreSQL drops the lock with the aborted one.
     */
    @Test
    void aFailingUnlockAbortsTheConnectionAndThePoolLendsAnotherSession() throws SQLException {
        Faults faults = new Faults(dataSource, Fault.UNLOCK_THROWS);
        try (HikariDataSource pool = poolOfOne(faults.dataSource())) {
            int pidBefore = backendPid(pool);
            LoadLock.Lease lease = new PostgresLoadLock(pool).tryAcquire().orElseThrow();

            lease.close();

            assertThat(faults.aborted).isTrue();
            assertThat(backendPid(pool)).as("the pool evicted the aborted session").isNotEqualTo(pidBefore);
            assertTheLockIsFreeOnceTheSessionEnds();
        }
    }

    /**
     * An unlock that returns false while the session still holds the lock (the SQL is swapped for one
     * that answers false and releases nothing): only the abort can free the lock here.
     */
    @Test
    void anUnlockReturningFalseAbortsTheConnectionAndThePoolLendsAnotherSession() throws SQLException {
        Faults faults = new Faults(dataSource, Fault.UNLOCK_RETURNS_FALSE);
        try (HikariDataSource pool = poolOfOne(faults.dataSource())) {
            int pidBefore = backendPid(pool);
            LoadLock.Lease lease = new PostgresLoadLock(pool).tryAcquire().orElseThrow();

            lease.close();

            assertThat(faults.aborted).isTrue();
            assertThat(backendPid(pool)).as("the pool evicted the aborted session").isNotEqualTo(pidBefore);
            assertTheLockIsFreeOnceTheSessionEnds();
        }
    }

    /**
     * {@code pg_try_advisory_lock} is granted but reading its answer fails with a state that is not a
     * connection error (08): the session may hold the lock, so it is aborted, never returned to the
     * pool still holding it.
     */
    @Test
    void aGrantedLockWhoseAnswerCannotBeReadAbortsTheConnection() throws SQLException {
        Faults faults = new Faults(dataSource, Fault.TRY_LOCK_ANSWER_FAILS);
        try (HikariDataSource pool = poolOfOne(faults.dataSource())) {
            int pidBefore = backendPid(pool);

            assertThatThrownBy(() -> new PostgresLoadLock(pool).tryAcquire())
                    .isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(faults.aborted).isTrue();
            assertThat(backendPid(pool)).as("the pool evicted the aborted session").isNotEqualTo(pidBefore);
            assertTheLockIsFreeOnceTheSessionEnds();
        }
    }

    private static HikariDataSource poolOfOne(DataSource target) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(target);
        config.setMaximumPoolSize(1);
        config.setPoolName("load-lock-test");
        return new HikariDataSource(config);
    }

    private static int backendPid(DataSource pool) throws SQLException {
        try (Connection connection = pool.getConnection(); Statement sql = connection.createStatement();
                var rs = sql.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** A blocking lock with a timeout waits for the aborted backend to end, without sleeping. */
    private void assertTheLockIsFreeOnceTheSessionEnds() throws SQLException {
        try (Connection other = dataSource.getConnection(); Statement sql = other.createStatement()) {
            sql.execute("SET lock_timeout = '5s'");
            sql.execute("SELECT pg_advisory_lock(" + PostgresLoadLock.LOAD_LOCK_KEY + ")");
            sql.execute("SELECT pg_advisory_unlock(" + PostgresLoadLock.LOAD_LOCK_KEY + ")");
        }
    }

    private enum Fault { UNLOCK_THROWS, UNLOCK_RETURNS_FALSE, TRY_LOCK_ANSWER_FAILS }

    /** A DataSource whose connections break one statement of the lock and record {@code abort}. */
    private static final class Faults {

        private final DataSource target;
        private final Fault fault;
        volatile boolean aborted;

        Faults(DataSource target, Fault fault) {
            this.target = target;
            this.fault = fault;
        }

        DataSource dataSource() {
            return (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("getConnection")) {
                            return invoke(method, target, args);
                        }
                        return connection(target.getConnection());
                    });
        }

        private Connection connection(Connection real) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("abort")) {
                            aborted = true;
                        }
                        if (method.getName().equals("prepareStatement")) {
                            String sql = (String) args[0];
                            if (sql.contains("pg_advisory_unlock(") && fault == Fault.UNLOCK_THROWS) {
                                throw new SQLException("canceling statement due to statement timeout", "57014");
                            }
                            if (sql.contains("pg_advisory_unlock(") && fault == Fault.UNLOCK_RETURNS_FALSE) {
                                return real.prepareStatement("SELECT ?::bigint IS NULL");
                            }
                            if (sql.contains("pg_try_advisory_lock(") && fault == Fault.TRY_LOCK_ANSWER_FAILS) {
                                try (Statement take = real.createStatement()) {
                                    take.execute("SELECT pg_try_advisory_lock(" + PostgresLoadLock.LOAD_LOCK_KEY + ")");
                                }
                                throw new SQLException("could not read the answer", "XX000");
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
