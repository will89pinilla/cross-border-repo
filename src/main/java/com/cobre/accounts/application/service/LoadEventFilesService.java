package com.cobre.accounts.application.service;

import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.LoadEventFilesUseCase;
import com.cobre.accounts.application.port.in.LoadInProgressException;
import com.cobre.accounts.application.port.in.LoadResult;
import com.cobre.accounts.application.port.in.LoadResult.EventResult;
import com.cobre.accounts.application.port.in.LoadResult.LoadedFile;
import com.cobre.accounts.application.port.in.PostSettlementUseCase;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.application.port.in.SettlementOutcome;
import com.cobre.accounts.application.port.in.SettlementOutcome.Applied;
import com.cobre.accounts.application.port.in.SettlementOutcome.Duplicate;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import com.cobre.accounts.application.port.out.AccountReadModel;
import com.cobre.accounts.application.port.out.LoadLock;
import com.cobre.accounts.application.service.DependencyScheduler.Job;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.SettlementDecision;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A load (spec §7.3):
 *
 * <ol>
 *   <li>Only one at a time ({@link LoadLock}); a refused load reads nothing (AC16).
 *   <li>The events of all files, in D4 order.
 *   <li>Duplicates in the load: the first by D4 is posted; the others wait for its decision and
 *       never touch the database (D6, §7.5).
 *   <li>The other events go through {@link DependencyScheduler} (D5), one transaction each.
 * </ol>
 */
public final class LoadEventFilesService implements LoadEventFilesUseCase {

    /**
     * D4 and §7.3: operation_date (absent or invalid = oldest), event_id (absent or not text = ""),
     * file name, position, and last the payload hash, for two files with the same name. Texts with
     * {@link String#compareTo}.
     */
    static final Comparator<ReadEvent> D4_ORDER = Comparator
            .comparing((ReadEvent e) -> e.operationDate() == null ? Instant.MIN : e.operationDate())
            .thenComparing(e -> e.eventId() == null ? "" : e.eventId())
            .thenComparing(ReadEvent::fileName)
            .thenComparingInt(ReadEvent::position)
            .thenComparing(ReadEvent::payloadHash, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final Comparator<LoadedFile> FILE_ORDER = Comparator.comparing(LoadedFile::name)
            .thenComparing(LoadedFile::status)
            .thenComparingInt(LoadedFile::eventsRead);

    private final PostSettlementUseCase postSettlement;
    private final LoadLock loadLock;
    private final AccountReadModel readModel;
    private final DependencyScheduler scheduler;
    private final Clock clock;
    private final Supplier<UUID> loadIds;

    public LoadEventFilesService(PostSettlementUseCase postSettlement, LoadLock loadLock, AccountReadModel readModel,
            int maxConcurrency, Clock clock, Supplier<UUID> loadIds) {
        this.postSettlement = Objects.requireNonNull(postSettlement, "postSettlement");
        this.loadLock = Objects.requireNonNull(loadLock, "loadLock");
        this.readModel = Objects.requireNonNull(readModel, "readModel");
        this.scheduler = new DependencyScheduler(maxConcurrency);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.loadIds = Objects.requireNonNull(loadIds, "loadIds");
    }

    @Override
    public LoadResult load(Supplier<List<EventFile>> readFiles) {
        Objects.requireNonNull(readFiles, "readFiles");
        LoadLock.Lease lease = loadLock.tryAcquire().orElseThrow(LoadInProgressException::new);
        try (lease) {
            UUID loadId = loadIds.get();
            Instant startedAt = clock.instant();
            List<EventFile> files = readFiles.get();
            List<ReadEvent> events = files.stream()
                    .flatMap(file -> file.events().stream())
                    .sorted(D4_ORDER)
                    .toList();
            List<SettlementOutcome> outcomes = decide(events, loadId);
            List<EventResult> results = new ArrayList<>(events.size());
            for (int i = 0; i < events.size(); i++) {
                results.add(new EventResult(events.get(i), outcomes.get(i)));
            }
            List<LoadedFile> loaded = files.stream()
                    .map(file -> new LoadedFile(file.name(), file.status(), file.events().size()))
                    .sorted(FILE_ORDER)
                    .toList();
            // Still under the lock: no other load can move these balances before the report is written.
            Set<AccountId> withLines = results.stream()
                    .filter(result -> result.outcome() instanceof Applied)
                    .flatMap(result -> ((Applied) result.outcome()).entry().lines().stream())
                    .map(EntryLine::accountId)
                    .collect(Collectors.toSet());
            Map<AccountId, BigDecimal> finalBalances = readModel.balancesOf(withLines);
            SortedMap<CurrencyCode, BigDecimal> invariant = readModel.invariant();
            return new LoadResult(loadId, startedAt, clock.instant(), scheduler.maxConcurrency(), loaded, results,
                    finalBalances, invariant);
        }
    }

    /** @return the outcome of each event, in the order of {@code events} */
    private List<SettlementOutcome> decide(List<ReadEvent> events, UUID loadId) {
        // For each event: its job, or -1 when it repeats an earlier event_id of the load.
        int[] jobOf = new int[events.size()];
        int[] firstOf = new int[events.size()];
        Map<String, Integer> firstById = new HashMap<>();
        List<Job<SettlementOutcome>> jobs = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            ReadEvent event = events.get(i);
            Integer first = event.hasIdentity() ? firstById.putIfAbsent(event.eventId(), i) : null;
            if (first != null) {
                jobOf[i] = -1;
                firstOf[i] = first;
                continue;
            }
            jobOf[i] = jobs.size();
            jobs.add(new Job<>(clientAccounts(event), () -> postSettlement.post(event, loadId)));
        }
        List<SettlementOutcome> decided = scheduler.run(jobs);
        List<SettlementOutcome> outcomes = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            if (jobOf[i] >= 0) {
                outcomes.add(decided.get(jobOf[i]));
            } else {
                int first = firstOf[i];
                outcomes.add(duplicateOf(events.get(first), decided.get(jobOf[first])));
            }
        }
        return outcomes;
    }

    /**
     * D5 keys: the CLIENT accounts of the event as written in it, whether they exist or not (M1), so
     * two events that open the same D7 account are ordered. Cobre accounts are never locked nor
     * checked (D10); an event that failed rules 1 or 2 touches no account.
     */
    private static Set<AccountId> clientAccounts(ReadEvent event) {
        if (!(event.outcome() instanceof ReadEvent.Translated translated)) {
            return Set.of();
        }
        return Stream.of(translated.command().originAccountId(), translated.command().destinationAccountId())
                .filter(id -> !id.isCobreReserved())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** §7.5: a later event of the load with the first's event_id gets the first's decision and stored hash. */
    private static Duplicate duplicateOf(ReadEvent first, SettlementOutcome firstOutcome) {
        String eventId = first.eventId();
        return switch (firstOutcome) {
            case Applied applied -> new Duplicate(SettlementDecision.applied(eventId, applied.entry().id()),
                    first.payloadHash());
            case Rejected rejected -> {
                if (!rejected.decisionStored()) {
                    throw new IllegalStateException("Event " + eventId + " has identity but stored no decision");
                }
                yield new Duplicate(SettlementDecision.rejected(eventId, rejected.code(), rejected.detail()),
                        first.payloadHash());
            }
            case Duplicate duplicate -> duplicate;
        };
    }
}
