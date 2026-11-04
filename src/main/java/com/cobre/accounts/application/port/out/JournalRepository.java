package com.cobre.accounts.application.port.out;

import com.cobre.accounts.domain.model.JournalEntry;

/** Append-only journal (D13): there is no update and no delete. */
public interface JournalRepository {

    /**
     * Inserts the entry and its lines ({@code line_no} 1..n in list order, in one batch).
     *
     * @throws DecisionAlreadyExistsException if an entry for the same {@code sourceEventId} exists:
     *     another process already decided that event (spec §7.3)
     */
    void insert(JournalEntry entry);
}
