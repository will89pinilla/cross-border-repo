package com.cobre.accounts.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Invariants of {@code SettlementDecision} (spec §3.4, §9). */
class SettlementDecisionTest {

    private static final String EVENT = "cbmm_20250909_000123";
    private static final EntryId ENTRY = new EntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void applied_hasEntryIdAndNoReason() {
        SettlementDecision decision = SettlementDecision.applied(EVENT, ENTRY);
        assertThat(decision.decision()).isEqualTo(DecisionType.APPLIED);
        assertThat(decision.entryId()).isEqualTo(ENTRY);
        assertThat(decision.reasonCode()).isNull();
        assertThat(decision.reasonDetail()).isNull();
    }

    @Test
    void rejected_hasReasonAndNoEntry() {
        SettlementDecision decision =
                SettlementDecision.rejected(EVENT, RejectionCode.CURRENCY_MISMATCH, "ORIGIN");
        assertThat(decision.decision()).isEqualTo(DecisionType.REJECTED);
        assertThat(decision.reasonCode()).isEqualTo(RejectionCode.CURRENCY_MISMATCH);
        assertThat(decision.reasonDetail()).isEqualTo("ORIGIN");
        assertThat(decision.entryId()).isNull();
    }

    @Test
    void rejected_detailIsOptional() {
        // INSUFFICIENT_FUNDS has no detail (spec §7.2).
        assertThat(SettlementDecision.rejected(EVENT, RejectionCode.INSUFFICIENT_FUNDS, null).reasonDetail())
                .isNull();
    }

    @Test
    void applied_requiresEntryId() {
        assertThatThrownBy(() -> SettlementDecision.applied(EVENT, null))
                .isInstanceOf(InvalidSettlementDecisionException.class);
    }

    @Test
    void rejected_requiresReasonCode() {
        assertThatThrownBy(() -> SettlementDecision.rejected(EVENT, null, "ORIGIN"))
                .isInstanceOf(InvalidSettlementDecisionException.class);
    }

    @Test
    void applied_cannotCarryReason() {
        assertThatThrownBy(() -> new SettlementDecision(
                        EVENT, DecisionType.APPLIED, ENTRY, RejectionCode.INSUFFICIENT_FUNDS, null))
                .isInstanceOf(InvalidSettlementDecisionException.class);
        assertThatThrownBy(() -> new SettlementDecision(EVENT, DecisionType.APPLIED, ENTRY, null, "ORIGIN"))
                .isInstanceOf(InvalidSettlementDecisionException.class);
    }

    @Test
    void rejected_cannotCarryEntry() {
        assertThatThrownBy(() -> new SettlementDecision(
                        EVENT, DecisionType.REJECTED, ENTRY, RejectionCode.INSUFFICIENT_FUNDS, null))
                .isInstanceOf(InvalidSettlementDecisionException.class);
    }

    @Test
    void sourceEventIdRequired() {
        assertThatThrownBy(() -> SettlementDecision.applied("", ENTRY)).isInstanceOf(InvalidIdentifierException.class);
        assertThatThrownBy(() -> SettlementDecision.applied(null, ENTRY)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectionCodes_matchSpecSection7_2() {
        assertThat(RejectionCode.values()).extracting(Enum::name).containsExactly(
                "UNSUPPORTED_EVENT_TYPE", "INVALID_EVENT", "ACCOUNT_NOT_FOUND",
                "CURRENCY_MISMATCH", "ACCOUNT_INACTIVE", "INSUFFICIENT_FUNDS");
    }
}
