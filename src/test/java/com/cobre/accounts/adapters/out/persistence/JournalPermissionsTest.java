package com.cobre.accounts.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

/** AC18 / D13: the journal is immutable for the application role; on account only the balance changes. */
class JournalPermissionsTest extends PostgresIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    JdbcClient jdbc;

    private String accountId;
    private UUID entryId;
    private String sourceEventId;

    @BeforeEach
    void insertOneEntryAsApp() {
        accountId = "ACC_" + UUID.randomUUID().toString().substring(0, 8);
        entryId = UUID.randomUUID();
        sourceEventId = "evt_" + entryId;
        Timestamp postedAt = Timestamp.from(Instant.parse("2025-09-09T15:32:10Z"));

        jdbc.sql("INSERT INTO account (account_id, currency, type, status, balance) VALUES (?, 'MXN', 'CLIENT', 'ACTIVE', 0.00)")
                .params(accountId).update();
        jdbc.sql("INSERT INTO journal_entry (entry_id, source_event_id, posted_at) VALUES (?, ?, ?)")
                .params(entryId, sourceEventId, postedAt).update();
        jdbc.sql("""
                INSERT INTO entry_line (entry_id, line_no, account_id, direction, amount, currency, posted_at)
                VALUES (?, 1, ?, 'CREDIT', 10.00, 'MXN', ?)""")
                .params(entryId, accountId, postedAt).update();
        jdbc.sql("""
                INSERT INTO settlement_decision (source_event_id, decision, entry_id, payload_hash, load_id)
                VALUES (?, 'APPLIED', ?, 'test-hash', ?)""")
                .params(sourceEventId, entryId, UUID.randomUUID()).update();
    }

    @Test
    void AC18_connectedAsAppRole() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo(APP_ROLE);
    }

    @ParameterizedTest(name = "AC18_appRoleCannotUpdate[{0}]")
    @ValueSource(strings = {"entry_line", "journal_entry", "settlement_decision"})
    void AC18_appRoleCannotUpdateJournalTables(String table) {
        assertPermissionDenied(() -> jdbc.sql("UPDATE " + table + " SET entry_id = entry_id WHERE entry_id = ?")
                .params(entryId).update());
    }

    @ParameterizedTest(name = "AC18_appRoleCannotDelete[{0}]")
    @ValueSource(strings = {"entry_line", "journal_entry", "settlement_decision"})
    void AC18_appRoleCannotDeleteJournalTables(String table) {
        assertPermissionDenied(() -> jdbc.sql("DELETE FROM " + table + " WHERE entry_id = ?")
                .params(entryId).update());
    }

    @Test
    void AC18_appRoleCannotTruncateEntryLine() {
        assertPermissionDenied(() -> jdbc.sql("TRUNCATE entry_line").update());
    }

    @Test
    void AC18_appRoleCannotLockEntryLineForUpdate() {
        assertPermissionDenied(() -> jdbc.sql("SELECT line_no FROM entry_line WHERE entry_id = ? FOR UPDATE")
                .params(entryId).query(Integer.class).list());
    }

    @ParameterizedTest(name = "AC18_appRoleCannotUpdateAccount[{0}]")
    @ValueSource(strings = {"type", "currency", "status"})
    void AC18_appRoleCannotUpdateAccountColumnsOtherThanBalance(String column) {
        assertPermissionDenied(() -> jdbc.sql("UPDATE account SET " + column + " = " + column + " WHERE account_id = ?")
                .params(accountId).update());
    }

    @Test
    void AC18_appRoleCanReadJournal() {
        assertThat(jdbc.sql("SELECT count(*) FROM entry_line WHERE entry_id = ?").params(entryId)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT decision FROM settlement_decision WHERE source_event_id = ?")
                .params(sourceEventId).query(String.class).single()).isEqualTo("APPLIED");
    }

    @Test
    void AC18_appRoleCanLockAccountForUpdateAndUpdateBalance() {
        BigDecimal locked = jdbc.sql("SELECT balance FROM account WHERE account_id = ? FOR UPDATE")
                .params(accountId).query(BigDecimal.class).single();
        assertThat(locked).isEqualByComparingTo("0.00");

        int updated = jdbc.sql("UPDATE account SET balance = 10.00 WHERE account_id = ?").params(accountId).update();
        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.sql("SELECT balance FROM account WHERE account_id = ?").params(accountId)
                .query(BigDecimal.class).single()).isEqualByComparingTo("10.00");
    }

    private static void assertPermissionDenied(ThrowingCallable statement) {
        assertThatThrownBy(statement).satisfies(e -> {
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            assertThat(root).isInstanceOf(SQLException.class);
            assertThat(((SQLException) root).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
        });
    }
}
