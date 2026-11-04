package com.cobre.accounts.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.application.port.out.DecisionAlreadyExistsException;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.model.SettlementDecision;
import com.cobre.accounts.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One decision per event_id (D6), insert only (D13); the primary key backs step 1 of spec §7.3. */
class JdbcSettlementDecisionRepositoryTest extends PostgresIntegrationTest {

    private static final String HASH = "a".repeat(64);

    @Autowired
    JdbcClient jdbc;

    private JdbcSettlementDecisionRepository decisions;
    private String eventId;
    private UUID loadId;

    @BeforeEach
    void setUp() {
        decisions = new JdbcSettlementDecisionRepository(jdbc);
        eventId = "evt_" + UUID.randomUUID();
        loadId = UUID.randomUUID();
    }

    private EntryId insertEntry() {
        UUID entryId = UUID.randomUUID();
        jdbc.sql("INSERT INTO journal_entry (entry_id, source_event_id, posted_at) VALUES (?, ?, ?)")
                .params(entryId, eventId, Timestamp.from(Instant.parse("2025-09-09T15:32:10Z"))).update();
        return new EntryId(entryId);
    }

    @Test
    void appliedDecisionIsStoredAndReadBack() {
        SettlementDecision applied = SettlementDecision.applied(eventId, insertEntry());

        decisions.insert(applied, HASH, loadId);

        assertThat(decisions.find(eventId)).contains(applied);
        Map<String, Object> row = jdbc.sql("SELECT payload_hash, load_id FROM settlement_decision WHERE source_event_id = ?")
                .params(eventId).query().singleRow();
        assertThat(row).containsEntry("payload_hash", HASH).containsEntry("load_id", loadId);
    }

    @Test
    void rejectedDecisionIsStoredAndReadBack() {
        SettlementDecision withDetail = SettlementDecision.rejected(eventId, RejectionCode.CURRENCY_MISMATCH, "ORIGIN");
        SettlementDecision withoutDetail =
                SettlementDecision.rejected(eventId + "_b", RejectionCode.INSUFFICIENT_FUNDS, null);

        decisions.insert(withDetail, HASH, loadId);
        decisions.insert(withoutDetail, HASH, loadId);

        assertThat(decisions.find(eventId)).contains(withDetail);
        assertThat(decisions.find(eventId + "_b")).contains(withoutDetail);
    }

    @Test
    void unknownEventHasNoDecision() {
        assertThat(decisions.find(eventId)).isEmpty();
    }

    /** Spec §7.3: the second decision for an event fails on the primary key; the first one stays. */
    @Test
    void secondDecisionForTheSameEventIsRejectedByThePrimaryKey() {
        SettlementDecision first = SettlementDecision.rejected(eventId, RejectionCode.INSUFFICIENT_FUNDS, null);
        decisions.insert(first, HASH, loadId);

        assertThatThrownBy(() -> decisions.insert(SettlementDecision.applied(eventId, insertEntry()), HASH, loadId))
                .isInstanceOf(DecisionAlreadyExistsException.class)
                .satisfies(e -> assertThat(((DecisionAlreadyExistsException) e).sourceEventId()).isEqualTo(eventId));
        assertThat(decisions.find(eventId)).contains(first);
    }

    /** Review finding 5: only the primary key means "already decided"; another unique key is a bug. */
    @Test
    void reusingAnEntryForAnotherEventIsNotADuplicateDecision() {
        EntryId entry = insertEntry();
        decisions.insert(SettlementDecision.applied(eventId, entry), HASH, loadId);

        assertThatThrownBy(() -> decisions.insert(SettlementDecision.applied(eventId + "_other", entry), HASH, loadId))
                .isInstanceOf(DuplicateKeyException.class)
                .isNotInstanceOf(DecisionAlreadyExistsException.class)
                .hasMessageContaining("settlement_decision_entry_uk");
    }
}
