package com.cobre.accounts.adapters.in.rest;

import com.cobre.accounts.application.port.in.LoadResult;
import com.cobre.accounts.application.port.in.LoadResult.EventResult;
import com.cobre.accounts.application.port.in.SettlementOutcome.Applied;
import com.cobre.accounts.application.port.in.SettlementOutcome.Duplicate;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.JournalEntry;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The load report of spec §8.1. Amounts are text with exactly 2 decimals and dates ISO-8601 UTC,
 * built here as strings, so the JSON does not depend on how a mapper is configured. Only
 * {@code metadata} changes between two runs of the same files (D4, D5, AC11).
 */
public record LoadReport(
        List<FileView> files,
        Summary summary,
        List<AccountView> accounts,
        List<RejectedView> rejected,
        List<DuplicateView> duplicates,
        Map<String, String> invariant,
        Metadata metadata) {

    public record FileView(
            String name,
            String status,
            @JsonProperty("events_read") int eventsRead) {}

    public record Summary(
            @JsonProperty("events_read") long eventsRead,
            long applied,
            long rejected,
            long duplicates) {}

    public record AccountView(
            @JsonProperty("account_id") String accountId,
            String currency,
            @JsonProperty("final_balance") String finalBalance,
            List<LineView> transactions) {}

    /** One entry line; the same shape as an element of {@code /transactions} (spec §8.2). */
    public record LineView(
            @JsonProperty("source_event_id") String sourceEventId,
            String direction,
            String amount,
            @JsonProperty("posted_at") String postedAt) {}

    /**
     * @param eventId the text received, even without identity (too long or not ASCII), so the
     *     operator can find it; null only when absent or not text (§8.1)
     * @param detail null when the code has none
     */
    public record RejectedView(
            @JsonProperty("event_id") String eventId,
            String file,
            int position,
            String code,
            String detail,
            @JsonProperty("decision_stored") boolean decisionStored) {}

    public record DuplicateView(
            @JsonProperty("event_id") String eventId,
            String file,
            int position,
            @JsonProperty("original_decision") String originalDecision,
            @JsonProperty("payload_mismatch") boolean payloadMismatch) {}

    public record Metadata(
            @JsonProperty("load_id") String loadId,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("finished_at") String finishedAt,
            @JsonProperty("max_concurrency") int maxConcurrency) {}

    /** An entry line with its {@code line_no} (1-based, in entry order, as stored, §9). */
    private record NumberedLine(JournalEntry entry, int lineNo, EntryLine line) {}

    /** Lines of an account: {@code posted_at}, {@code source_event_id} ({@link String#compareTo}), line number (§8.1). */
    private static final Comparator<NumberedLine> LINE_ORDER = Comparator
            .comparing((NumberedLine l) -> l.line().postedAt())
            .thenComparing(l -> l.entry().sourceEventId())
            .thenComparingInt(NumberedLine::lineNo);

    public static LoadReport from(LoadResult result) {
        List<FileView> files = result.files().stream()
                .map(f -> new FileView(f.name(), f.status().name(), f.eventsRead()))
                .toList();
        Summary summary = new Summary(result.eventsRead(), result.applied(), result.rejected(), result.duplicates());
        List<RejectedView> rejected = new ArrayList<>();
        List<DuplicateView> duplicates = new ArrayList<>();
        // AccountId order is by code point, the same as String#compareTo (§8.1: accounts by account_id).
        Map<AccountId, List<NumberedLine>> linesByAccount = new TreeMap<>();
        for (EventResult event : result.events()) {
            switch (event.outcome()) {
                case Applied applied -> {
                    List<EntryLine> lines = applied.entry().lines();
                    for (int i = 0; i < lines.size(); i++) {
                        linesByAccount.computeIfAbsent(lines.get(i).accountId(), k -> new ArrayList<>())
                                .add(new NumberedLine(applied.entry(), i + 1, lines.get(i)));
                    }
                }
                case Rejected r -> rejected.add(new RejectedView(event.event().eventId(), event.event().fileName(),
                        event.event().position(), r.code().name(), r.detail(), r.decisionStored()));
                case Duplicate d -> duplicates.add(new DuplicateView(event.event().eventId(),
                        event.event().fileName(), event.event().position(), d.originalDecision().name(),
                        event.payloadMismatch()));
            }
        }
        List<AccountView> accounts = new ArrayList<>();
        linesByAccount.forEach((id, lines) -> {
            BigDecimal balance = result.finalBalances().get(id);
            if (balance == null) {
                throw new IllegalStateException("No final balance for " + id + ", which has lines in the load");
            }
            List<LineView> views = lines.stream().sorted(LINE_ORDER)
                    .map(l -> new LineView(l.entry().sourceEventId(), l.line().direction().name(),
                            amount(l.line().amount().amount()), instant(l.line().postedAt())))
                    .toList();
            accounts.add(new AccountView(id.value(), lines.getFirst().line().currency().value(), amount(balance), views));
        });
        Map<String, String> invariant = new LinkedHashMap<>();
        result.invariant().forEach((currency, total) -> invariant.put(currency.value(), amount(total)));
        Metadata metadata = new Metadata(result.loadId().toString(), instant(result.startedAt()),
                instant(result.finishedAt()), result.maxConcurrency());
        return new LoadReport(files, summary, accounts, rejected, duplicates, invariant, metadata);
    }

    /** Text with exactly 2 decimals and no thousands separator; never rounds (D11). */
    static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** ISO-8601 in UTC, as {@link Instant#toString}: {@code 2025-09-09T15:32:10Z}. */
    static String instant(Instant value) {
        return value.toString();
    }
}
