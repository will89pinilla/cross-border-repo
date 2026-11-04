package com.cobre.accounts.adapters.out.persistence;

import com.cobre.accounts.application.port.out.DecisionAlreadyExistsException;
import com.cobre.accounts.application.port.out.SettlementDecisionRepository;
import com.cobre.accounts.application.port.out.StoredDecision;
import com.cobre.accounts.domain.model.DecisionType;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.model.SettlementDecision;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code settlement_decision}: INSERT only (D13); its primary key backs D6 (spec §7.3). */
public final class JdbcSettlementDecisionRepository implements SettlementDecisionRepository {

    private final JdbcClient jdbc;

    public JdbcSettlementDecisionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<StoredDecision> find(String sourceEventId) {
        return jdbc.sql("""
                        SELECT source_event_id, decision, entry_id, reason_code, reason_detail, payload_hash
                        FROM settlement_decision WHERE source_event_id = ?""")
                .params(sourceEventId)
                .query((rs, row) -> {
                    UUID entryId = rs.getObject("entry_id", UUID.class);
                    String reasonCode = rs.getString("reason_code");
                    var decision = new SettlementDecision(
                            rs.getString("source_event_id"),
                            DecisionType.valueOf(rs.getString("decision")),
                            entryId == null ? null : new EntryId(entryId),
                            reasonCode == null ? null : RejectionCode.valueOf(reasonCode),
                            rs.getString("reason_detail"));
                    return new StoredDecision(decision, rs.getString("payload_hash"));
                })
                .optional();
    }

    @Override
    public void insert(SettlementDecision decision, String payloadHash, UUID loadId) {
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(loadId, "loadId");
        try {
            jdbc.sql("""
                            INSERT INTO settlement_decision
                                (source_event_id, decision, reason_code, reason_detail, entry_id, payload_hash, load_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""")
                    .params(decision.sourceEventId(), decision.decision().name(),
                            decision.reasonCode() == null ? null : decision.reasonCode().name(),
                            decision.reasonDetail(),
                            decision.entryId() == null ? null : decision.entryId().value(),
                            payloadHash, loadId)
                    .update();
        } catch (DuplicateKeyException e) {
            // Only the primary key means "already decided"; any other unique key is a bug, rethrown as is.
            if (UniqueViolation.on(e, UniqueViolation.DECISION_PK)) {
                throw new DecisionAlreadyExistsException(decision.sourceEventId(), e);
            }
            throw e;
        }
    }
}
