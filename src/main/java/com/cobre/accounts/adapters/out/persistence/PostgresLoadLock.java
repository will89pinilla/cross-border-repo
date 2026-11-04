package com.cobre.accounts.adapters.out.persistence;

import com.cobre.accounts.application.port.out.LoadLock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * {@link LoadLock} with a session-level PostgreSQL advisory lock (spec §7.3): every instance that
 * shares the database sees it. The lease keeps its own connection, outside any transaction, from
 * {@code pg_try_advisory_lock} until {@code pg_advisory_unlock}; that is one pool connection
 * besides the {@code max-concurrency} ones of the events (§11).
 *
 * <p>Releasing never throws: it runs when the load already ended, and must neither turn a finished
 * load into a 500 nor hide the load's own exception. If the unlock throws or returns false, it logs
 * a WARN and aborts the connection: PostgreSQL ends the session and drops its lock, and the pool
 * evicts the dead connection instead of lending a session that still holds the lock.
 */
public final class PostgresLoadLock implements LoadLock {

    private static final Logger log = LoggerFactory.getLogger(PostgresLoadLock.class);

    /** "CBMM" in ASCII: the only advisory lock key the module uses. */
    public static final long LOAD_LOCK_KEY = 0x43424D4DL;

    private final DataSource dataSource;

    public PostgresLoadLock(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<Lease> tryAcquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            if (call(connection, "SELECT pg_try_advisory_lock(?)")) {
                return Optional.of(new PostgresLease(connection));
            }
            connection.close();
            return Optional.empty();
        } catch (SQLException e) {
            // The lock may have been granted before the failure: never return that session to the pool.
            abortQuietly(connection, e);
            throw new DataAccessResourceFailureException("Cannot take the load lock", e);
        }
    }

    private static boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOAD_LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /**
     * Ends the session, so PostgreSQL drops any lock it holds, and closes the handle: on an aborted
     * connection the pool's return check fails with 08003 and it evicts it.
     */
    private static void abortQuietly(Connection connection, Exception cause) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Closing the aborted connection of load lock {} failed", LOAD_LOCK_KEY, e);
        }
    }

    private static final class PostgresLease implements Lease {

        private final Connection connection;
        private final AtomicBoolean closed = new AtomicBoolean();

        PostgresLease(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            boolean released;
            try {
                released = call(connection, "SELECT pg_advisory_unlock(?)");
                if (!released) {
                    log.warn("Load lock {} was not held by its lease; aborting its connection", LOAD_LOCK_KEY);
                }
            } catch (SQLException e) {
                released = false;
                log.warn("Cannot release load lock {}; aborting its connection", LOAD_LOCK_KEY, e);
            }
            if (released) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.warn("Cannot close the connection of load lock {}", LOAD_LOCK_KEY, e);
                }
                return;
            }
            abort();
        }

        private void abort() {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException e) {
                log.warn("Cannot abort the connection of load lock {}", LOAD_LOCK_KEY, e);
            }
            try {
                // On an aborted connection the pool's reset fails with 08003 and it evicts it.
                connection.close();
            } catch (SQLException e) {
                log.debug("Closing the aborted connection of load lock {} failed", LOAD_LOCK_KEY, e);
            }
        }
    }
}
