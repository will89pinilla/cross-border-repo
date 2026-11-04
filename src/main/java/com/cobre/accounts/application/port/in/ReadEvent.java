package com.cobre.accounts.application.port.in;

import com.cobre.accounts.domain.model.PostSettlement;
import com.cobre.accounts.domain.model.RejectionCode;
import java.time.Instant;
import java.util.Objects;

/**
 * A CBMM event as read from a file, after rules 1 and 2 of spec §7.2.
 *
 * <p>Every event read, valid or not, keeps what the load needs to order it (D4, §7.3) and to report
 * it by file and position.
 *
 * @param position index in the array or among the non-empty lines (spec §6.2)
 * @param eventId the {@code event_id} text as received, even when too long; null when absent or not
 *     text. Ordering treats null as the empty text (§7.3)
 * @param operationDate null when absent or invalid; ordering treats it as the oldest date (§7.3)
 * @param hasIdentity the {@code event_id} is usable as identity, so a decision can be stored (D6).
 *     False when it is absent, blank, not text, not printable ASCII or longer than 128 characters
 *     (§7.2)
 * @param payloadHash {@code payload_hash} of spec §9 (SHA-256 hex of the canonical JSON); null only
 *     when the event is not a JSON object ({@code MALFORMED_JSON})
 */
public record ReadEvent(
        String fileName,
        int position,
        String eventId,
        Instant operationDate,
        boolean hasIdentity,
        String payloadHash,
        Outcome outcome) {

    public ReadEvent {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(outcome, "outcome");
        if (position < 0) {
            throw new IllegalArgumentException("Negative position " + position);
        }
        if (hasIdentity && eventId == null) {
            throw new IllegalArgumentException("An event with identity has an event id");
        }
        if (hasIdentity && payloadHash == null) {
            throw new IllegalArgumentException("An event with identity has a payload hash: " + eventId);
        }
        if (outcome instanceof Translated translated && !translated.command().sourceEventId().equals(eventId)) {
            throw new IllegalArgumentException("Translated event id differs: " + eventId);
        }
    }

    /** Result of rules 1 and 2. */
    public sealed interface Outcome {}

    /** Passed rules 1 and 2; rules 3 to 6 run in its transaction (§7.2). */
    public record Translated(PostSettlement command) implements Outcome {

        public Translated {
            Objects.requireNonNull(command, "command");
        }
    }

    /**
     * Failed rule 1 ({@code UNSUPPORTED_EVENT_TYPE}, no detail) or rule 2 ({@code INVALID_EVENT} with
     * one of its details).
     */
    public record Invalid(RejectionCode code, String detail) implements Outcome {

        public Invalid {
            Objects.requireNonNull(code, "code");
            if (code != RejectionCode.UNSUPPORTED_EVENT_TYPE && code != RejectionCode.INVALID_EVENT) {
                throw new IllegalArgumentException("Rules 3 to 6 are not checked when reading: " + code);
            }
            if ((code == RejectionCode.INVALID_EVENT) != (detail != null)) {
                throw new IllegalArgumentException("INVALID_EVENT, and only it, has a detail: " + code + "/" + detail);
            }
        }
    }
}
