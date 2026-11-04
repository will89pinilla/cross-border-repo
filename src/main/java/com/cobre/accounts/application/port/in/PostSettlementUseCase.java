package com.cobre.accounts.application.port.in;

import java.util.UUID;

/**
 * Decides one CBMM event in its own transaction (spec §7.3): duplicate check, rules 3 to 6 with
 * the CLIENT accounts locked (D12), the entry and the decision. Events are posted in the order and
 * with the parallelism the load decides (D4, D5); this use case knows nothing about other events.
 */
public interface PostSettlementUseCase {

    /**
     * @param event an event after rules 1 and 2
     * @param loadId the load that decides it, stored with the decision (spec §9)
     * @throws com.cobre.accounts.application.port.out.RetriesExhaustedException if the third
     *     attempt also fails with a deadlock or a serialization failure (spec §7.3)
     */
    SettlementOutcome post(ReadEvent event, UUID loadId);
}
