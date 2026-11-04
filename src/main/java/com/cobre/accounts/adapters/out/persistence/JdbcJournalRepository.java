package com.cobre.accounts.adapters.out.persistence;

import com.cobre.accounts.application.port.out.DecisionAlreadyExistsException;
import com.cobre.accounts.application.port.out.JournalRepository;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.JournalEntry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code journal_entry} and {@code entry_line}: INSERT only (D13). Lines go in one JDBC batch (spec §7.3). */
public final class JdbcJournalRepository implements JournalRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;

    public JdbcJournalRepository(JdbcClient jdbc, JdbcTemplate batch) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.batch = Objects.requireNonNull(batch, "batch");
    }

    /**
     * An APPLIED decision references its entry, so in a race the entry insert is the one that hits
     * the unique {@code source_event_id} first: it means the same as a duplicate decision (spec §7.3).
     */
    @Override
    public void insert(JournalEntry entry) {
        try {
            jdbc.sql("INSERT INTO journal_entry (entry_id, source_event_id, posted_at) VALUES (?, ?, ?)")
                    .params(entry.id().value(), entry.sourceEventId(), utc(entry.postedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            // Only source_event_id means "already decided"; an entry_id clash is a bug, rethrown as is.
            if (UniqueViolation.on(e, UniqueViolation.ENTRY_SOURCE_EVENT_UK)) {
                throw new DecisionAlreadyExistsException(entry.sourceEventId(), e);
            }
            throw e;
        }
        List<Object[]> lines = new ArrayList<>(entry.lines().size());
        int lineNo = 1;
        for (EntryLine line : entry.lines()) {
            lines.add(new Object[] {
                entry.id().value(), lineNo++, line.accountId().value(), line.direction().name(),
                line.amount().amount(), line.currency().value(), utc(line.postedAt())
            });
        }
        batch.batchUpdate("""
                INSERT INTO entry_line (entry_id, line_no, account_id, direction, amount, currency, posted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", lines);
    }

    /** timestamptz from an explicit offset, so the stored instant never depends on the JVM time zone. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
