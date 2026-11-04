package com.cobre.accounts.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identity of a {@link JournalEntry} ({@code journal_entry.entry_id uuid}). */
public record EntryId(UUID value) {

    public EntryId {
        Objects.requireNonNull(value, "entryId");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
