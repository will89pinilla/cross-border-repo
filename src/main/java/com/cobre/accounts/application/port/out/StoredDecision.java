package com.cobre.accounts.application.port.out;

import com.cobre.accounts.domain.model.SettlementDecision;
import java.util.Objects;

/**
 * A decision as stored in {@code settlement_decision}, with the {@code payload_hash} of the event
 * that got it: a later event with the same {@code event_id} is a duplicate, and
 * {@code payload_mismatch} compares its hash with this one (spec §7.5).
 */
public record StoredDecision(SettlementDecision decision, String payloadHash) {

    public StoredDecision {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(payloadHash, "payloadHash");
    }
}
