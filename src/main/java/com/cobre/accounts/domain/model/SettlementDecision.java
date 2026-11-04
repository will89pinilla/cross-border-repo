package com.cobre.accounts.domain.model;

import java.util.Objects;

/**
 * Entity (spec §3.4): the one decision per {@code sourceEventId} (D6). APPLIED has an
 * {@code entryId} and no reason; REJECTED has a {@code reasonCode} and no entry. Immutable.
 *
 * <p>{@code reasonDetail} holds the short codes of spec §7.2 ({@code ORIGIN}, {@code MISSING_FIELD},
 * ...) and may be null, e.g. for {@code INSUFFICIENT_FUNDS}. {@code payload_hash} and
 * {@code load_id} are recorded by the application when it stores the decision (spec §7.3, §9).
 */
public record SettlementDecision(
        String sourceEventId,
        DecisionType decision,
        EntryId entryId,
        RejectionCode reasonCode,
        String reasonDetail) {

    public SettlementDecision {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(decision, "decision");
        if (sourceEventId.isBlank()) {
            throw new InvalidIdentifierException("Settlement decision without source event id");
        }
        boolean consistent = switch (decision) {
            case APPLIED -> entryId != null && reasonCode == null && reasonDetail == null;
            case REJECTED -> reasonCode != null && entryId == null;
        };
        if (!consistent) {
            throw new InvalidSettlementDecisionException("Inconsistent " + decision + " decision for "
                    + sourceEventId + ": entryId=" + entryId + ", reasonCode=" + reasonCode
                    + ", reasonDetail=" + reasonDetail);
        }
    }

    public static SettlementDecision applied(String sourceEventId, EntryId entryId) {
        return new SettlementDecision(sourceEventId, DecisionType.APPLIED, entryId, null, null);
    }

    public static SettlementDecision rejected(String sourceEventId, RejectionCode reasonCode, String reasonDetail) {
        return new SettlementDecision(sourceEventId, DecisionType.REJECTED, null, reasonCode, reasonDetail);
    }
}
