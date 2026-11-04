package com.cobre.accounts.application.port.in;

import com.cobre.accounts.domain.model.DecisionType;
import com.cobre.accounts.domain.model.JournalEntry;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.model.SettlementDecision;
import java.util.Objects;

/** What {@link PostSettlementUseCase} did with one event (spec §7.3). */
public sealed interface SettlementOutcome {

    /** Rules 3 to 6 passed: the entry, the balances and the APPLIED decision are committed. */
    record Applied(JournalEntry entry) implements SettlementOutcome {

        public Applied {
            Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * The first failing rule of spec §7.2. A rejection has no effects on balances, lines or accounts.
     *
     * @param detail null when the code has none ({@code UNSUPPORTED_EVENT_TYPE}, {@code INSUFFICIENT_FUNDS})
     * @param decisionStored false only for an event without identity (§7.2), which is reported by
     *     file and position and stores nothing
     */
    record Rejected(RejectionCode code, String detail, boolean decisionStored) implements SettlementOutcome {

        public Rejected {
            Objects.requireNonNull(code, "code");
        }
    }

    /**
     * The {@code event_id} already had a decision (D6): nothing was evaluated or written.
     *
     * @param original the decision kept for that {@code event_id}
     * @param originalPayloadHash the {@code payload_hash} stored with it; the event is a
     *     {@code payload_mismatch} when its own hash differs (spec §7.5)
     */
    record Duplicate(SettlementDecision original, String originalPayloadHash) implements SettlementOutcome {

        public Duplicate {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(originalPayloadHash, "originalPayloadHash");
        }

        public DecisionType originalDecision() {
            return original.decision();
        }
    }
}
