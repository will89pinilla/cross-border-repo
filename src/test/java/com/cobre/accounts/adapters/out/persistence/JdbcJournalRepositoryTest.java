package com.cobre.accounts.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.application.port.out.DecisionAlreadyExistsException;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Journal inserts (spec §7.1, §9): entry and lines in order; one entry per source event. */
class JdbcJournalRepositoryTest extends PostgresIntegrationTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Instant AT = Instant.parse("2025-09-09T15:32:10Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private JdbcJournalRepository journal;
    private Account origin;
    private Account destination;

    @BeforeEach
    void setUp() {
        journal = new JdbcJournalRepository(jdbc, jdbcTemplate);
        JdbcAccountRepository accounts = new JdbcAccountRepository(jdbc);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        origin = Account.client(AccountId.of("ORIG_" + suffix), MXN, AccountStatus.ACTIVE, Money.of("1000.00", MXN));
        destination = Account.client(AccountId.of("DEST_" + suffix), USD, AccountStatus.ACTIVE, Money.zero(USD));
        accounts.insertIfAbsent(origin);
        accounts.insertIfAbsent(destination);
    }

    /** The four lines of spec §7.1, in order. */
    private JournalEntry caseEntry(EntryId id, String sourceEventId) {
        Money paid = Money.of("1000.00", MXN);
        Money received = Money.of("58.75", USD);
        return JournalEntry.of(id, sourceEventId, AT, List.of(
                EntryLine.of(origin, Direction.DEBIT, paid, AT),
                EntryLine.of(Account.fxPosition(MXN, AccountStatus.ACTIVE), Direction.CREDIT, paid, AT),
                EntryLine.of(Account.fxPosition(USD, AccountStatus.ACTIVE), Direction.DEBIT, received, AT),
                EntryLine.of(destination, Direction.CREDIT, received, AT)));
    }

    @Test
    void insertsTheEntryAndItsLinesInOrder() {
        EntryId id = new EntryId(UUID.randomUUID());
        String eventId = "evt_" + id;

        journal.insert(caseEntry(id, eventId));

        Map<String, Object> entry = jdbc.sql("SELECT source_event_id, posted_at FROM journal_entry WHERE entry_id = ?")
                .params(id.value()).query().singleRow();
        assertThat(entry.get("source_event_id")).isEqualTo(eventId);
        assertThat(((java.sql.Timestamp) entry.get("posted_at")).toInstant()).isEqualTo(AT);

        List<Map<String, Object>> lines = jdbc.sql("""
                SELECT line_no, account_id, direction, amount, currency, posted_at
                FROM entry_line WHERE entry_id = ? ORDER BY line_no""").params(id.value()).query().listOfRows();
        assertThat(lines).extracting(row -> ((Number) row.get("line_no")).intValue()).containsExactly(1, 2, 3, 4);
        assertThat(lines).extracting(row -> row.get("account_id")).containsExactly(
                origin.id().value(), "COBRE_FX_POSITION_MXN", "COBRE_FX_POSITION_USD", destination.id().value());
        assertThat(lines).extracting(row -> row.get("direction")).containsExactly("DEBIT", "CREDIT", "DEBIT", "CREDIT");
        assertThat(lines).extracting(row -> ((BigDecimal) row.get("amount")).toPlainString())
                .containsExactly("1000.00", "1000.00", "58.75", "58.75");
        assertThat(lines).extracting(row -> row.get("currency")).containsExactly("MXN", "MXN", "USD", "USD");
        assertThat(lines).allSatisfy(row ->
                assertThat(((java.sql.Timestamp) row.get("posted_at")).toInstant()).isEqualTo(AT));
    }

    @Test
    void postedAtKeepsTheInstantWhateverTheSessionTimeZone() {
        EntryId id = new EntryId(UUID.randomUUID());
        journal.insert(caseEntry(id, "evt_" + id));

        OffsetDateTime stored = jdbc.sql("SELECT posted_at FROM journal_entry WHERE entry_id = ?")
                .params(id.value()).query(OffsetDateTime.class).single();
        assertThat(stored.toInstant()).isEqualTo(AT);
    }

    /** Spec §7.3: a second entry for the same event means another process already decided it. */
    @Test
    void secondEntryForTheSameSourceEventIsADecisionAlreadyExists() {
        String eventId = "evt_" + UUID.randomUUID();
        journal.insert(caseEntry(new EntryId(UUID.randomUUID()), eventId));
        EntryId second = new EntryId(UUID.randomUUID());

        assertThatThrownBy(() -> journal.insert(caseEntry(second, eventId)))
                .isInstanceOf(DecisionAlreadyExistsException.class)
                .satisfies(e -> assertThat(((DecisionAlreadyExistsException) e).sourceEventId()).isEqualTo(eventId));
        assertThat(jdbc.sql("SELECT count(*) FROM entry_line WHERE entry_id = ?").params(second.value())
                .query(Integer.class).single()).isZero();
    }

    /** Review finding 5: only the source_event_id key means "already decided"; an entry_id clash is a bug. */
    @Test
    void reusedEntryIdIsNotADecisionAlreadyExists() {
        EntryId id = new EntryId(UUID.randomUUID());
        journal.insert(caseEntry(id, "evt_" + id));

        assertThatThrownBy(() -> journal.insert(caseEntry(id, "evt_other_" + id)))
                .isInstanceOf(DuplicateKeyException.class)
                .isNotInstanceOf(DecisionAlreadyExistsException.class)
                .hasMessageContaining("journal_entry_pk");
    }
}
