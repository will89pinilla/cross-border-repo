package com.cobre.accounts.application.port.out;

import com.cobre.accounts.domain.model.SettlementDecision;
import java.util.Optional;
import java.util.UUID;

/** One decision per {@code event_id}, for the life of the module (D6). Insert only (D13). */
public interface SettlementDecisionRepository {

    Optional<SettlementDecision> find(String sourceEventId);

    /**
     * @param payloadHash hash of the event content (algorithm fixed in T05, spec §9)
     * @param loadId the load that decided the event
     * @throws DecisionAlreadyExistsException if the event already has a decision (spec §7.3)
     */
    void insert(SettlementDecision decision, String payloadHash, UUID loadId);
}
