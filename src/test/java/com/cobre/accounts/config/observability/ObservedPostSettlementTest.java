package com.cobre.accounts.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.application.port.in.PostSettlementUseCase;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.application.port.in.SettlementOutcome;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import com.cobre.accounts.domain.model.RejectionCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Spec §11 (D15): every log line written while an event is posted (among them the
 * {@code EntriesPosted} and {@code PostingRejected} of §8.3) carries {@code load_id} and
 * {@code event_id}; {@code cbmm_event_posting_seconds} times each posting.
 */
class ObservedPostSettlementTest {

    private static final UUID LOAD_ID = UUID.fromString("8f6d2c1e-0b7a-4c7e-9a51-2f0d6b9e4a10");
    private static final Rejected REJECTED = new Rejected(RejectionCode.INSUFFICIENT_FUNDS, null, true);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void S11_mdcHasLoadIdAndEventIdDuringPosting() {
        Map<String, String> seen = new HashMap<>();
        PostSettlementUseCase real = (event, loadId) -> {
            seen.putAll(MDC.getCopyOfContextMap());
            return REJECTED;
        };

        SettlementOutcome outcome = new ObservedPostSettlement(real, registry).post(event("cbmm_1"), LOAD_ID);

        assertThat(outcome).isSameAs(REJECTED);
        assertThat(seen).containsEntry("load_id", LOAD_ID.toString()).containsEntry("event_id", "cbmm_1");
        assertThat(MDC.get("load_id")).isNull();
        assertThat(MDC.get("event_id")).isNull();
    }

    /** T09 notes: a rejection is found in its file by name and position, as in the report (§8.1). */
    @Test
    void S11_mdcHasFileAndPositionDuringPosting() {
        Map<String, String> seen = new HashMap<>();
        PostSettlementUseCase real = (event, loadId) -> {
            seen.putAll(MDC.getCopyOfContextMap());
            return REJECTED;
        };

        new ObservedPostSettlement(real, registry).post(event("cbmm_1"), LOAD_ID);

        assertThat(seen).containsEntry("file", "a.json").containsEntry("position", "3");
        assertThat(MDC.get("file")).isNull();
        assertThat(MDC.get("position")).isNull();
    }

    @Test
    void S11_eventWithoutEventIdHasOnlyLoadId() {
        Map<String, String> seen = new HashMap<>();
        PostSettlementUseCase real = (event, loadId) -> {
            seen.putAll(MDC.getCopyOfContextMap());
            return new Rejected(RejectionCode.INVALID_EVENT, "MISSING_FIELD", false);
        };

        new ObservedPostSettlement(real, registry).post(event(null), LOAD_ID);

        assertThat(seen).containsEntry("load_id", LOAD_ID.toString()).doesNotContainKey("event_id");
    }

    @Test
    void S11_mdcClearedAfterException() {
        PostSettlementUseCase failing = (event, loadId) -> {
            throw new IllegalStateException("retries exhausted");
        };

        assertThatThrownBy(() -> new ObservedPostSettlement(failing, registry).post(event("cbmm_1"), LOAD_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("load_id")).isNull();
        assertThat(MDC.get("event_id")).isNull();
    }

    @Test
    void S11_restoresWhatTheThreadHadBefore() {
        MDC.put("load_id", "outer-load");
        MDC.put("event_id", "outer-event");
        MDC.put("file", "outer.json");
        MDC.put("position", "9");

        new ObservedPostSettlement((event, loadId) -> REJECTED, registry).post(event("cbmm_1"), LOAD_ID);

        assertThat(MDC.get("load_id")).isEqualTo("outer-load");
        assertThat(MDC.get("event_id")).isEqualTo("outer-event");
        assertThat(MDC.get("file")).isEqualTo("outer.json");
        assertThat(MDC.get("position")).isEqualTo("9");
    }

    @Test
    void S11_recordsPostingTime() {
        ObservedPostSettlement observed = new ObservedPostSettlement((event, loadId) -> REJECTED, registry);

        observed.post(event("cbmm_1"), LOAD_ID);
        observed.post(event("cbmm_2"), LOAD_ID);

        var timer = registry.get("cbmm.event.posting").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.getId().getTags()).isEmpty();
    }

    @Test
    void S11_failedPostingIsTimedToo() {
        PostSettlementUseCase failing = (event, loadId) -> {
            throw new IllegalStateException("retries exhausted");
        };

        assertThatThrownBy(() -> new ObservedPostSettlement(failing, registry).post(event("cbmm_1"), LOAD_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("cbmm.event.posting").timer().count()).isEqualTo(1);
    }

    private static ReadEvent event(String eventId) {
        return new ReadEvent("a.json", 3, eventId, Instant.parse("2025-09-09T10:00:00Z"), eventId != null,
                "a".repeat(64), new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MISSING_FIELD"));
    }
}
