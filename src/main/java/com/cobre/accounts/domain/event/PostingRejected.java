package com.cobre.accounts.domain.event;

import com.cobre.accounts.domain.model.RejectionCode;
import java.util.Objects;

/**
 * The event was rejected and its REJECTED decision is committed (spec §8.3).
 *
 * @param detail the detail of spec §7.2; null when the code has none
 */
public record PostingRejected(String sourceEventId, RejectionCode code, String detail) implements DomainEvent {

    public PostingRejected {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(code, "code");
    }
}
