package com.cobre.accounts.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.FileStatus;
import com.cobre.accounts.application.port.in.LoadEventFilesUseCase;
import com.cobre.accounts.application.port.in.LoadInProgressException;
import com.cobre.accounts.application.port.in.LoadResult;
import com.cobre.accounts.application.port.in.LoadResult.EventResult;
import com.cobre.accounts.application.port.in.LoadResult.LoadedFile;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.application.port.in.SettlementOutcome;
import com.cobre.accounts.application.port.in.SettlementOutcome.Applied;
import com.cobre.accounts.application.port.in.SettlementOutcome.Duplicate;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Direction;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.JournalEntry;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.model.SettlementDecision;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Spec §11 (D15): {@code cbmm_events_total{result,code}}, {@code cbmm_files_total{status}} and
 * {@code cbmm_load_duration_seconds}, from the {@link LoadResult} of each load. No metric has an
 * {@code account_id} tag.
 */
class ObservedLoadEventFilesTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void S11_countsEventsByResultAndCode() {
        LoadResult result = result(List.of(), List.of(
                applied("e1"),
                applied("e2"),
                rejected("e3", RejectionCode.INSUFFICIENT_FUNDS),
                rejected("e4", RejectionCode.INSUFFICIENT_FUNDS),
                rejected("e5", RejectionCode.CURRENCY_MISMATCH),
                duplicate("e1")));

        new ObservedLoadEventFiles(files -> result, registry).load(List::of);

        assertThat(eventCounts()).containsExactlyEntriesOf(new TreeMap<>(Map.of(
                "applied/none", 2.0,
                "duplicate/none", 1.0,
                "rejected/CURRENCY_MISMATCH", 1.0,
                "rejected/INSUFFICIENT_FUNDS", 2.0)));
    }

    @Test
    void S11_countsAccumulateAcrossLoads() {
        LoadResult result = result(List.of(), List.of(applied("e1")));
        ObservedLoadEventFiles observed = new ObservedLoadEventFiles(files -> result, registry);

        observed.load(List::of);
        observed.load(List::of);

        assertThat(eventCounts()).containsExactlyEntriesOf(Map.of("applied/none", 2.0));
    }

    @Test
    void S11_countsFilesByStatus() {
        LoadResult result = result(List.of(
                new LoadedFile("a.json", FileStatus.PROCESSED, 1),
                new LoadedFile("b.json", FileStatus.PROCESSED, 0),
                new LoadedFile("c.json", FileStatus.UNREADABLE, 0)), List.of());

        new ObservedLoadEventFiles(files -> result, registry).load(List::of);

        assertThat(registry.get("cbmm.files").tag("status", "processed").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("cbmm.files").tag("status", "unreadable").counter().count()).isEqualTo(1.0);
    }

    @Test
    void S11_recordsLoadDuration() {
        LoadResult result = result(List.of(), List.of(applied("e1")));

        new ObservedLoadEventFiles(files -> result, registry).load(List::of);

        var timer = registry.get("cbmm.load.duration").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTags()).isEmpty();
    }

    @Test
    void S11_delegatesTheSameResultAndReaderUntouched() {
        LoadResult result = result(List.of(), List.of(applied("e1")));
        AtomicBoolean readerCalled = new AtomicBoolean();
        LoadEventFilesUseCase real = files -> {
            files.get();
            return result;
        };

        LoadResult returned = new ObservedLoadEventFiles(real, registry).load(() -> {
            readerCalled.set(true);
            return List.<EventFile>of();
        });

        assertThat(returned).isSameAs(result);
        assertThat(readerCalled).isTrue();
    }

    @Test
    void S11_refusedLoadRecordsNothing() {
        LoadEventFilesUseCase busy = files -> {
            throw new LoadInProgressException();
        };

        assertThatThrownBy(() -> new ObservedLoadEventFiles(busy, registry).load(List::of))
                .isInstanceOf(LoadInProgressException.class);

        assertThat(registry.get("cbmm.load.duration").timer().count()).isZero();
        assertThat(registry.find("cbmm.events").counters()).isEmpty();
        assertThat(registry.find("cbmm.files").counters()).isEmpty();
    }

    @Test
    void S11_failedLoadRethrowsAndCountsNothing() {
        LoadEventFilesUseCase failing = files -> {
            throw new IllegalStateException("database down");
        };

        assertThatThrownBy(() -> new ObservedLoadEventFiles(failing, registry).load(List::of))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database down");

        assertThat(registry.get("cbmm.load.duration").timer().count()).isZero();
        assertThat(registry.find("cbmm.events").counters()).isEmpty();
        assertThat(registry.find("cbmm.files").counters()).isEmpty();
    }

    @Test
    void S11_noMeterHasAnAccountIdTag() {
        LoadResult result = result(List.of(new LoadedFile("a.json", FileStatus.PROCESSED, 3)), List.of(
                applied("e1"), rejected("e2", RejectionCode.ACCOUNT_NOT_FOUND), duplicate("e1")));

        new ObservedLoadEventFiles(files -> result, registry).load(List::of);

        assertThat(registry.getMeters())
                .flatMap(meter -> meter.getId().getTags())
                .extracting(Tag::getKey)
                .containsOnly("result", "code", "status");
    }

    // --- Helpers ----------------------------------------------------------------------------

    private Map<String, Double> eventCounts() {
        return registry.find("cbmm.events").counters().stream().collect(Collectors.toMap(
                counter -> counter.getId().getTag("result") + "/" + counter.getId().getTag("code"),
                counter -> counter.count(),
                Double::sum,
                TreeMap::new));
    }

    private static LoadResult result(List<LoadedFile> files, List<EventResult> events) {
        return new LoadResult(UUID.randomUUID(), NOW, NOW.plusMillis(60), 16, files, events, Map.of(),
                new TreeMap<>());
    }

    private static EventResult applied(String eventId) {
        Account a = Account.client(AccountId.of("ACC_A"), MXN, AccountStatus.ACTIVE, Money.of("10.00", MXN));
        Account b = Account.client(AccountId.of("ACC_B"), MXN, AccountStatus.ACTIVE, Money.of("0.00", MXN));
        JournalEntry entry = JournalEntry.of(new EntryId(UUID.randomUUID()), eventId, NOW, List.of(
                EntryLine.of(a, Direction.DEBIT, Money.of("1.00", MXN), NOW),
                EntryLine.of(b, Direction.CREDIT, Money.of("1.00", MXN), NOW)));
        return result(eventId, new Applied(entry));
    }

    private static EventResult rejected(String eventId, RejectionCode code) {
        return result(eventId, new Rejected(code, code == RejectionCode.ACCOUNT_NOT_FOUND ? "ORIGIN" : null, true));
    }

    private static EventResult duplicate(String eventId) {
        return result(eventId, new Duplicate(SettlementDecision.applied(eventId, new EntryId(UUID.randomUUID())), HASH));
    }

    private static EventResult result(String eventId, SettlementOutcome outcome) {
        ReadEvent event = new ReadEvent("a.json", 0, eventId, NOW, true, HASH,
                new ReadEvent.Invalid(RejectionCode.UNSUPPORTED_EVENT_TYPE, null));
        return new EventResult(event, outcome);
    }
}
