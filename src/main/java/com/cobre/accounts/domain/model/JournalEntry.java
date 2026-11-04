package com.cobre.accounts.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Aggregate root (spec §3.4): immutable record of an applied event. At least two lines, amounts
 * greater than 0, each line in its account's currency (guaranteed by {@link EntryLine}), and
 * debits equal to credits in each currency. Line order is kept: it is {@code line_no} (spec §7.1).
 */
public final class JournalEntry {

    private final EntryId id;
    private final String sourceEventId;
    private final Instant postedAt;
    private final List<EntryLine> lines;

    private JournalEntry(EntryId id, String sourceEventId, Instant postedAt, List<EntryLine> lines) {
        this.id = id;
        this.sourceEventId = sourceEventId;
        this.postedAt = postedAt;
        this.lines = lines;
    }

    public static JournalEntry of(EntryId id, String sourceEventId, Instant postedAt, List<EntryLine> lines) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(postedAt, "postedAt");
        List<EntryLine> copy = List.copyOf(lines);
        if (sourceEventId.isBlank()) {
            throw new InvalidIdentifierException("Journal entry without source event id");
        }
        if (copy.size() < 2) {
            throw new UnbalancedEntryException("Journal entry " + sourceEventId + " has " + copy.size() + " line(s)");
        }
        Map<CurrencyCode, Money> net = netByCurrency(copy);
        net.forEach((currency, sum) -> {
            if (sum.amount().signum() != 0) {
                throw new UnbalancedEntryException(
                        "Journal entry " + sourceEventId + " does not balance in " + currency + ": " + sum);
            }
        });
        return new JournalEntry(id, sourceEventId, postedAt, copy);
    }

    /** Credits minus debits per currency, ordered by currency. */
    private static Map<CurrencyCode, Money> netByCurrency(List<EntryLine> lines) {
        Map<CurrencyCode, Money> net = new TreeMap<>((a, b) -> a.value().compareTo(b.value()));
        for (EntryLine line : lines) {
            net.merge(line.currency(), line.signedAmount(), Money::plus);
        }
        return net;
    }

    public EntryId id() {
        return id;
    }

    public String sourceEventId() {
        return sourceEventId;
    }

    public Instant postedAt() {
        return postedAt;
    }

    /** Unmodifiable, in {@code line_no} order. */
    public List<EntryLine> lines() {
        return lines;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JournalEntry other
                && id.equals(other.id)
                && sourceEventId.equals(other.sourceEventId)
                && postedAt.equals(other.postedAt)
                && lines.equals(other.lines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sourceEventId, postedAt, lines);
    }

    @Override
    public String toString() {
        return "JournalEntry[" + id + ", " + sourceEventId + ", " + postedAt + ", " + lines + "]";
    }
}
