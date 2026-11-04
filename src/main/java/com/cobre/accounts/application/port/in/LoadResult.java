package com.cobre.accounts.application.port.in;

import com.cobre.accounts.application.port.in.SettlementOutcome.Applied;
import com.cobre.accounts.application.port.in.SettlementOutcome.Duplicate;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What a load did (spec §8.1), before it is written as the JSON report. Everything except
 * {@code loadId}, {@code startedAt}, {@code finishedAt} and {@code maxConcurrency} (the
 * {@code metadata}) is deterministic (D4, D5, AC11).
 *
 * @param files ordered by name (then status and events read, so that two files with the same name
 *     come out the same whatever the upload order)
 * @param events every event read, in D4 order, with what was done with it
 * @param finalBalances balance of every account with lines in this load, read while the load still
 *     holds its lock, so no later load can change it before the report is written (spec §8.1, AC11)
 * @param invariant per currency, the sum of the balances of all accounts, read under the same lock
 */
public record LoadResult(
        UUID loadId,
        Instant startedAt,
        Instant finishedAt,
        int maxConcurrency,
        List<LoadedFile> files,
        List<EventResult> events,
        Map<AccountId, BigDecimal> finalBalances,
        SortedMap<CurrencyCode, BigDecimal> invariant) {

    public LoadResult {
        Objects.requireNonNull(loadId, "loadId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        files = List.copyOf(files);
        events = List.copyOf(events);
        finalBalances = Map.copyOf(finalBalances);
        invariant = Collections.unmodifiableSortedMap(new TreeMap<>(invariant));
    }

    /** A file of the load: {@code PROCESSED} or {@code UNREADABLE}, and its events read. */
    public record LoadedFile(String name, FileStatus status, int eventsRead) {

        public LoadedFile {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
        }
    }

    /** One event and its outcome. */
    public record EventResult(ReadEvent event, SettlementOutcome outcome) {

        public EventResult {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(outcome, "outcome");
        }

        /**
         * Spec §7.5: a duplicate whose {@code payload_hash} differs from the one stored with the
         * original decision, whether this load or an earlier one took it. False when not a duplicate.
         */
        public boolean payloadMismatch() {
            return outcome instanceof Duplicate duplicate
                    && !duplicate.originalPayloadHash().equals(event.payloadHash());
        }
    }

    public long eventsRead() {
        return events.size();
    }

    public long applied() {
        return count(Applied.class);
    }

    public long rejected() {
        return count(Rejected.class);
    }

    public long duplicates() {
        return count(Duplicate.class);
    }

    private long count(Class<? extends SettlementOutcome> type) {
        return events.stream().filter(e -> type.isInstance(e.outcome())).count();
    }
}
