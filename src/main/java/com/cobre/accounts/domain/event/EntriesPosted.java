package com.cobre.accounts.domain.event;

import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.EntryLine;
import java.util.List;
import java.util.Objects;

/** The event was applied: its entry and lines are committed (spec §8.3). */
public record EntriesPosted(String sourceEventId, EntryId entryId, List<EntryLine> lines) implements DomainEvent {

    public EntriesPosted {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(entryId, "entryId");
        lines = List.copyOf(lines);
    }
}
