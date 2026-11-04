package com.cobre.accounts.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.FileStatus;
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
import com.cobre.accounts.application.port.in.AccountBalance;
import com.cobre.accounts.application.port.in.EntryLinePage;
import com.cobre.accounts.application.port.out.AccountReadModel;
import com.cobre.accounts.application.port.out.LoadLock;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.PostSettlement;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.model.SettlementDecision;
import com.cobre.accounts.domain.service.PostingResult.Posted;
import com.cobre.accounts.domain.service.PostingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The load with doubles of its ports (spec §12.2): order D4, duplicates in the load, D5 keys and the lock. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class LoadEventFilesServiceTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final AccountId MX = AccountId.of("ACC_MX");
    private static final AccountId US = AccountId.of("ACC_US");
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final UUID LOAD_ID = UUID.fromString("8f6d2c1e-0b7a-4c7e-9a51-2f0d6b9e4a10");
    private static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");

    private final FakeLock lock = new FakeLock();
    private final FakePosting posting = new FakePosting();

    private final FakeReadModel readModel = new FakeReadModel(lock);

    private LoadEventFilesService service(int maxConcurrency) {
        return new LoadEventFilesService(posting, lock, readModel, maxConcurrency, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> LOAD_ID);
    }

    // --- Final balances and invariant of the report (spec §8.1) -----------------------------

    @Test
    void AC11_finalBalancesOfAccountsWithLinesAndInvariantAreReadWhileTheLockIsHeld() {
        posting.applyAll = true;
        posting.outcomes.put("e2", e -> new Rejected(RejectionCode.INSUFFICIENT_FUNDS, null, true));
        AccountId other = AccountId.of("ACC_OTHER");
        readModel.balances.putAll(Map.of(MX, new BigDecimal("990.00"), US, new BigDecimal("1.00"),
                AccountId.fxPosition(MXN), new BigDecimal("10.00"), AccountId.fxPosition(USD), new BigDecimal("-1.00"),
                other, new BigDecimal("5.00")));
        readModel.invariant.put(USD, new BigDecimal("0.00"));
        readModel.invariant.put(MXN, new BigDecimal("0.00"));

        LoadResult result = service(4).load(() -> List.of(file("a.json",
                event("a.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US),
                event("a.json", 1, "e2", "2025-09-09T11:00:00Z", US, AccountId.of("ACC_REJECTED")))));

        // Only the accounts with lines in this load: e2 was rejected, ACC_OTHER was not touched.
        assertThat(result.finalBalances()).containsOnly(
                Map.entry(MX, new BigDecimal("990.00")), Map.entry(US, new BigDecimal("1.00")),
                Map.entry(AccountId.fxPosition(MXN), new BigDecimal("10.00")),
                Map.entry(AccountId.fxPosition(USD), new BigDecimal("-1.00")));
        assertThat(result.invariant().keySet()).containsExactly(MXN, USD);
        assertThat(readModel.readWithoutLock).isFalse();
        assertThat(readModel.reads.get()).isEqualTo(2);
    }

    @Test
    void AC11_aLoadWithoutAppliedEventsHasNoFinalBalancesButStillItsInvariant() {
        readModel.invariant.put(MXN, new BigDecimal("0.00"));

        LoadResult result = service(4).load(() -> List.of(file("a.json",
                event("a.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US))));

        assertThat(result.finalBalances()).isEmpty();
        assertThat(result.invariant()).containsOnly(Map.entry(MXN, new BigDecimal("0.00")));
        assertThat(readModel.readWithoutLock).isFalse();
    }

    // --- Order D4 ---------------------------------------------------------------------------

    @Test
    void eventsAreDecidedInD4OrderWhateverTheFileOrder() {
        EventFile second = file("b.json",
                event("b.json", 0, "e3", "2025-09-09T10:30:00Z", MX, US),
                event("b.json", 1, "e2", "2025-09-09T09:00:00Z", US, MX));
        EventFile first = file("a.json", event("a.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US));

        LoadResult result = service(1).load(() -> List.of(second, first));

        assertThat(posting.postedIds()).containsExactly("e2", "e1", "e3");
        assertThat(ids(result)).containsExactly("e2", "e1", "e3");
    }

    @Test
    void sameDateOrdersByEventIdThenFileThenPosition() {
        String date = "2025-09-09T10:00:00Z";
        EventFile b = file("b.json", event("b.json", 0, "e2", date, MX, US), event("b.json", 1, "e1", date, US, MX));
        EventFile a = file("a.json", event("a.json", 0, "e2", date, MX, US, OTHER_HASH));

        LoadResult result = service(1).load(() -> List.of(b, a));

        assertThat(result.events()).extracting(e -> e.event().eventId() + "@" + e.event().fileName() + ":" + e.event().position())
                .containsExactly("e1@b.json:1", "e2@a.json:0", "e2@b.json:0");
    }

    /** Spec §7.3: an absent or invalid date is the oldest; an absent or non-text event_id is "". */
    @Test
    void absentOrInvalidDateSortsFirstAndAbsentEventIdAsEmptyText() {
        EventFile f = file("f.json",
                event("f.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US),
                invalid("f.json", 1, "z_invalid_date", null, RejectionCode.INVALID_EVENT, "INVALID_DATE"),
                invalid("f.json", 2, null, null, RejectionCode.INVALID_EVENT, "MISSING_FIELD"),
                invalid("f.json", 3, "a_no_date", null, RejectionCode.INVALID_EVENT, "MISSING_FIELD"));

        LoadResult result = service(1).load(() -> List.of(f));

        assertThat(result.events()).extracting(e -> e.event().position()).containsExactly(2, 3, 1, 0);
    }

    /** Spec §7.3: two files with the same name; the last tie-break is the payload hash. */
    @Test
    void sameFileNameAndPositionTieBreaksByPayloadHash() {
        String date = "2025-09-09T10:00:00Z";
        // Same date, no event_id (""), same file name and position: only the hash tells them apart.
        EventFile one = file("same.json", anonymous("same.json", 0, date, OTHER_HASH));
        EventFile two = file("same.json", anonymous("same.json", 0, date, HASH));
        LoadResult forward = service(1).load(() -> List.of(one, two));
        LoadResult backward = service(1).load(() -> List.of(two, one));

        assertThat(forward.events()).extracting(e -> e.event().payloadHash()).containsExactly(HASH, OTHER_HASH);
        assertThat(backward.events()).isEqualTo(forward.events());
        assertThat(backward.files()).isEqualTo(forward.files());
    }

    @Test
    void filesAreListedByNameWithTheirStatusAndEventsRead() {
        EventFile b = file("b.json", event("b.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US));
        EventFile a = EventFile.unreadable("a.json");

        LoadResult result = service(4).load(() -> List.of(b, a));

        assertThat(result.files()).containsExactly(
                new LoadedFile("a.json", FileStatus.UNREADABLE, 0), new LoadedFile("b.json", FileStatus.PROCESSED, 1));
        assertThat(result.loadId()).isEqualTo(LOAD_ID);
        assertThat(result.startedAt()).isEqualTo(NOW);
        assertThat(result.finishedAt()).isEqualTo(NOW);
        assertThat(result.maxConcurrency()).isEqualTo(4);
        assertThat(posting.loadIds).containsOnly(LOAD_ID);
    }

    // --- Duplicates in the load (D6, §7.5) -----------------------------------------------

    @Test
    void AC03_duplicateInTheSameLoadIsNotPostedAndKeepsTheFirstsDecision() {
        posting.applyAll = true;
        ReadEvent original = event("a.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US);
        ReadEvent again = event("b.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US);

        LoadResult result = service(4).load(() -> List.of(file("b.json", again), file("a.json", original)));

        assertThat(posting.postedIds()).containsExactly("e1");
        Applied applied = (Applied) result.events().get(0).outcome();
        EventResult duplicate = result.events().get(1);
        assertThat(duplicate.event()).isEqualTo(again);
        assertThat(duplicate.outcome()).isEqualTo(new Duplicate(SettlementDecision.applied("e1", applied.entry().id()), HASH));
        assertThat(duplicate.payloadMismatch()).isFalse();
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(1);
    }

    @Test
    void AC17_sameEventIdWithOtherContentFirstByD4WinsAndTheOtherIsAPayloadMismatch() {
        posting.applyAll = true;
        // In file order the later one comes first; D4 (operation_date) puts it second.
        ReadEvent later = event("a.json", 0, "e1", "2025-09-09T11:00:00Z", MX, US, OTHER_HASH);
        ReadEvent earlier = event("a.json", 1, "e1", "2025-09-09T10:00:00Z", MX, US, HASH);

        LoadResult result = service(4).load(() -> List.of(file("a.json", later, earlier)));

        assertThat(posting.posted).containsExactly(earlier);
        assertThat(result.events()).extracting(EventResult::event).containsExactly(earlier, later);
        assertThat(result.events().get(1).outcome()).isInstanceOf(Duplicate.class);
        assertThat(result.events().get(1).payloadMismatch()).isTrue();
        assertThat(result.events().get(0).payloadMismatch()).isFalse();
    }

    @Test
    void AC04_duplicateOfARejectedFirstKeepsTheRejection() {
        ReadEvent first = event("a.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US);
        ReadEvent again = event("a.json", 1, "e1", "2025-09-09T10:00:00Z", MX, US);

        LoadResult result = service(1).load(() -> List.of(file("a.json", first, again)));

        assertThat(result.events().get(1).outcome()).isEqualTo(new Duplicate(
                SettlementDecision.rejected("e1", RejectionCode.INSUFFICIENT_FUNDS, null), HASH));
    }

    /** §7.5: inside a load, the original decision may come from an earlier load, with its stored hash. */
    @Test
    void AC03_duplicateInTheLoadOfAnEventDecidedInAnEarlierLoadComparesWithTheStoredHash() {
        SettlementDecision stored = SettlementDecision.rejected("e1", RejectionCode.INSUFFICIENT_FUNDS, null);
        posting.outcomes.put("e1", e -> new Duplicate(stored, OTHER_HASH));
        ReadEvent first = event("a.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US, OTHER_HASH);
        ReadEvent second = event("a.json", 1, "e1", "2025-09-09T10:00:00Z", MX, US, HASH);

        LoadResult result = service(1).load(() -> List.of(file("a.json", first, second)));

        assertThat(posting.posted).containsExactly(first);
        assertThat(result.events()).extracting(EventResult::outcome)
                .containsExactly(new Duplicate(stored, OTHER_HASH), new Duplicate(stored, OTHER_HASH));
        assertThat(result.events()).extracting(EventResult::payloadMismatch).containsExactly(false, true);
        assertThat(result.duplicates()).isEqualTo(2);
    }

    /** §7.5: an event without identity is never a duplicate, even with the same event_id text. */
    @Test
    void eventsWithoutIdentityAreNeverDuplicates() {
        String tooLong = "e".repeat(129);
        ReadEvent one = noIdentity("a.json", 0, tooLong);
        ReadEvent two = noIdentity("a.json", 1, tooLong);

        LoadResult result = service(4).load(() -> List.of(file("a.json", one, two)));

        assertThat(posting.posted).containsExactlyInAnyOrder(one, two);
        assertThat(result.events()).extracting(EventResult::outcome).containsOnly(
                new Rejected(RejectionCode.INVALID_EVENT, "FIELD_TOO_LONG", false));
        assertThat(result.duplicates()).isZero();
    }

    @Test
    void anInvalidEventWithIdentityAndAValidOneWithTheSameIdAreDuplicates() {
        ReadEvent invalid = invalid("a.json", 0, "e1", null, RejectionCode.INVALID_EVENT, "INVALID_DATE");
        ReadEvent valid = event("a.json", 1, "e1", "2025-09-09T10:00:00Z", MX, US);

        LoadResult result = service(4).load(() -> List.of(file("a.json", valid, invalid)));

        assertThat(posting.posted).containsExactly(invalid);
        assertThat(result.events().get(1).outcome()).isEqualTo(new Duplicate(
                SettlementDecision.rejected("e1", RejectionCode.INVALID_EVENT, "INVALID_DATE"), HASH));
    }

    // --- D5 keys --------------------------------------------------------------------------

    /** M1: the graph uses the account ids of the event, whether they exist or not. */
    @Test
    void eventsOnTheSameAccountNeverOverlapAndKeepD4Order() {
        AccountId unknown = AccountId.of("ACC_NEW");
        AtomicInteger active = new AtomicInteger();
        AtomicBoolean overlap = new AtomicBoolean();
        posting.during = e -> {
            if (active.incrementAndGet() > 1) {
                overlap.set(true);
            }
            Thread.yield();
            active.decrementAndGet();
        };
        List<ReadEvent> events = IntStream.range(0, 100)
                .mapToObj(i -> event("a.json", i, "e%03d".formatted(i), "2025-09-09T10:00:00Z",
                        AccountId.of("ACC_O" + i), unknown))
                .toList();

        service(16).load(() -> List.of(file("a.json", events.toArray(ReadEvent[]::new))));

        assertThat(overlap).isFalse();
        assertThat(posting.postedIds()).isSorted().hasSize(100);
    }

    /** §7.4: Cobre accounts do not count, so two events that share only one run in parallel. */
    @Test
    void cobreAccountsAndInvalidEventsAddNoDependencies() {
        CountDownLatch allStarted = new CountDownLatch(3);
        posting.during = e -> {
            allStarted.countDown();
            await(allStarted);
        };
        AccountId cobre = AccountId.fxPosition(MXN);
        ReadEvent one = event("a.json", 0, "e1", "2025-09-09T10:00:00Z", MX, cobre);
        ReadEvent two = event("a.json", 1, "e2", "2025-09-09T10:00:00Z", US, cobre);
        ReadEvent three = invalid("a.json", 2, "e3", null, RejectionCode.INVALID_EVENT, "INVALID_DATE");

        service(3).load(() -> List.of(file("a.json", one, two, three)));

        assertThat(allStarted.getCount()).isZero();
    }

    // --- Lock (AC16) and failures -----------------------------------------------------------

    @Test
    void AC16_aLoadWhileAnotherHoldsTheLockIsRefusedAndReadsNothing() {
        lock.free = false;
        AtomicBoolean read = new AtomicBoolean();

        assertThatThrownBy(() -> service(4).load(() -> {
            read.set(true);
            return List.of();
        })).isInstanceOf(LoadInProgressException.class);

        assertThat(read).isFalse();
        assertThat(posting.posted).isEmpty();
    }

    @Test
    void theLockIsHeldDuringTheLoadAndReleasedAfterIt() {
        posting.during = e -> assertThat(lock.held).isTrue();

        service(4).load(() -> List.of(file("a.json", event("a.json", 0, "e1", "2025-09-09T10:00:00Z", MX, US))));

        assertThat(lock.held).isFalse();
        assertThat(lock.acquisitions).isEqualTo(1);
    }

    @Test
    void anExceptionOfAnEventStopsTheLoadReleasesTheLockAndIsRethrown() {
        IllegalStateException boom = new IllegalStateException("boom");
        posting.outcomes.put("e005", e -> {
            throw boom;
        });
        // A chain on one account: nothing after e005 may run.
        List<ReadEvent> events = IntStream.range(0, 10)
                .mapToObj(i -> event("a.json", i, "e%03d".formatted(i), "2025-09-09T10:00:00Z", MX, US))
                .toList();

        assertThatThrownBy(() -> service(4).load(() -> List.of(file("a.json", events.toArray(ReadEvent[]::new)))))
                .isSameAs(boom);

        assertThat(posting.postedIds()).containsExactly("e000", "e001", "e002", "e003", "e004", "e005");
        assertThat(lock.held).isFalse();
    }

    @Test
    void anExceptionWhileReadingReleasesTheLock() {
        assertThatThrownBy(() -> service(4).load(() -> {
            throw new IllegalStateException("cannot read");
        })).hasMessage("cannot read");

        assertThat(lock.held).isFalse();
    }

    @Test
    void maxConcurrencyMustBeAtLeastOne() {
        assertThatThrownBy(() -> service(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Helpers ----------------------------------------------------------------------------

    private static List<String> ids(LoadResult result) {
        return result.events().stream().map(e -> e.event().eventId()).toList();
    }

    private static EventFile file(String name, ReadEvent... events) {
        return new EventFile(name, FileStatus.PROCESSED, List.of(events));
    }

    private static ReadEvent event(String file, int position, String id, String date, AccountId origin,
            AccountId destination) {
        return event(file, position, id, date, origin, destination, HASH);
    }

    private static ReadEvent event(String file, int position, String id, String date, AccountId origin,
            AccountId destination, String hash) {
        CurrencyCode originCurrency = origin.equals(US) ? USD : MXN;
        CurrencyCode destinationCurrency = originCurrency == MXN ? USD : MXN;
        PostSettlement command = new PostSettlement(id, Instant.parse(date), origin, Money.of("10.00", originCurrency),
                destination, Money.of("1.00", destinationCurrency));
        return new ReadEvent(file, position, id, command.operationDate(), true, hash, new ReadEvent.Translated(command));
    }

    private static ReadEvent invalid(String file, int position, String id, String date, RejectionCode code,
            String detail) {
        return new ReadEvent(file, position, id, date == null ? null : Instant.parse(date), id != null,
                id != null ? HASH : null, new ReadEvent.Invalid(code, detail));
    }

    /** Without event_id, so without identity: never a duplicate, ordered by its hash last. */
    private static ReadEvent anonymous(String file, int position, String date, String hash) {
        return new ReadEvent(file, position, null, Instant.parse(date), false, hash,
                new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MISSING_FIELD"));
    }

    private static ReadEvent noIdentity(String file, int position, String eventId) {
        return new ReadEvent(file, position, eventId, null, false, HASH,
                new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "FIELD_TOO_LONG"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class FakeLock implements LoadLock {
        volatile boolean free = true;
        volatile boolean held;
        int acquisitions;

        @Override
        public Optional<Lease> tryAcquire() {
            if (!free || held) {
                return Optional.empty();
            }
            held = true;
            acquisitions++;
            return Optional.of(() -> held = false);
        }
    }

    /** Balances and invariant as given; remembers whether any read happened without the load lock. */
    private static final class FakeReadModel implements AccountReadModel {
        final Map<AccountId, BigDecimal> balances = new HashMap<>();
        final SortedMap<CurrencyCode, BigDecimal> invariant = new TreeMap<>();
        final AtomicInteger reads = new AtomicInteger();
        final FakeLock lock;
        volatile boolean readWithoutLock;

        FakeReadModel(FakeLock lock) {
            this.lock = lock;
        }

        private void read() {
            reads.incrementAndGet();
            if (!lock.held) {
                readWithoutLock = true;
            }
        }

        @Override
        public Optional<AccountBalance> balanceOf(AccountId id) {
            throw new AssertionError("The load reads balances in one call");
        }

        @Override
        public Map<AccountId, BigDecimal> balancesOf(Collection<AccountId> ids) {
            read();
            Map<AccountId, BigDecimal> found = new HashMap<>();
            ids.forEach(id -> Optional.ofNullable(balances.get(id)).ifPresent(b -> found.put(id, b)));
            return found;
        }

        @Override
        public SortedMap<CurrencyCode, BigDecimal> invariant() {
            read();
            return new TreeMap<>(invariant);
        }

        @Override
        public Optional<EntryLinePage> linesOf(AccountId id, int limit, int offset) {
            throw new AssertionError("The load does not read lines");
        }
    }

    /**
     * Rejects every event like {@link PostSettlementService} would without the database: rules 1-2
     * as read, INSUFFICIENT_FUNDS for translated ones unless {@code applyAll}.
     */
    private static final class FakePosting implements PostSettlementUseCase {
        final List<ReadEvent> posted = Collections.synchronizedList(new ArrayList<>());
        final List<UUID> loadIds = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Function<ReadEvent, SettlementOutcome>> outcomes = new HashMap<>();
        volatile boolean applyAll;
        volatile java.util.function.Consumer<ReadEvent> during = e -> {};

        List<String> postedIds() {
            synchronized (posted) {
                return posted.stream().map(ReadEvent::eventId).toList();
            }
        }

        @Override
        public SettlementOutcome post(ReadEvent event, UUID loadId) {
            posted.add(event);
            loadIds.add(loadId);
            during.accept(event);
            var custom = outcomes.get(event.eventId());
            if (custom != null) {
                return custom.apply(event);
            }
            return switch (event.outcome()) {
                case ReadEvent.Invalid invalid -> new Rejected(invalid.code(), invalid.detail(), event.hasIdentity());
                case ReadEvent.Translated translated -> applyAll
                        ? new Applied(apply(translated.command()))
                        : new Rejected(RejectionCode.INSUFFICIENT_FUNDS, null, true);
            };
        }

        private static com.cobre.accounts.domain.model.JournalEntry apply(PostSettlement command) {
            Map<AccountId, Account> accounts = Map.of(
                    command.originAccountId(), Account.client(command.originAccountId(), command.originAmount().currency(),
                            AccountStatus.ACTIVE, Money.of("1000000.00", command.originAmount().currency())),
                    command.destinationAccountId(), Account.client(command.destinationAccountId(),
                            command.destinationAmount().currency(), AccountStatus.ACTIVE,
                            Money.of("0.00", command.destinationAmount().currency())));
            var result = new PostingService(() -> new EntryId(UUID.randomUUID()), false).post(command, accounts);
            return ((Posted) result).entry();
        }
    }
}
