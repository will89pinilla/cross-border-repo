package com.cobre.accounts.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.application.port.in.SettlementOutcome;
import com.cobre.accounts.application.port.in.SettlementOutcome.Applied;
import com.cobre.accounts.application.port.in.SettlementOutcome.Duplicate;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import com.cobre.accounts.application.port.out.AccountRepository;
import com.cobre.accounts.application.port.out.DecisionAlreadyExistsException;
import com.cobre.accounts.application.port.out.DomainEventPublisher;
import com.cobre.accounts.application.port.out.JournalRepository;
import com.cobre.accounts.application.port.out.SettlementDecisionRepository;
import com.cobre.accounts.application.port.out.UnitOfWork;
import com.cobre.accounts.domain.event.DomainEvent;
import com.cobre.accounts.domain.event.EntriesPosted;
import com.cobre.accounts.domain.event.PostingRejected;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.JournalEntry;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.PostSettlement;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.model.SettlementDecision;
import com.cobre.accounts.domain.service.PostingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The flow of spec §7.3 with in-memory doubles of the output ports: no database, no Spring. */
class PostSettlementServiceTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final AccountId ORIGIN = AccountId.of("ACC123456789");
    private static final AccountId DESTINATION = AccountId.of("ACC987654321");
    private static final String HASH = "a".repeat(64);
    private static final UUID LOAD = UUID.randomUUID();

    private final FakeUnitOfWork unitOfWork = new FakeUnitOfWork();
    private final FakeAccounts accounts = new FakeAccounts();
    private final FakeJournal journal = new FakeJournal();
    private final FakeDecisions decisions = new FakeDecisions();
    private final List<String> timeline = new ArrayList<>();
    private final List<DomainEvent> published = new ArrayList<>();
    private final DomainEventPublisher publisher = event -> {
        timeline.add(unitOfWork.active ? "publish-inside-transaction" : "publish");
        published.add(event);
    };

    private PostSettlementService service(boolean autoCreate) {
        return new PostSettlementService(accounts, journal, decisions, unitOfWork,
                new PostingService(() -> new EntryId(UUID.randomUUID()), autoCreate), publisher);
    }

    private static ReadEvent translated(String eventId, AccountId origin, String paid, AccountId destination,
            String received) {
        PostSettlement command = new PostSettlement(eventId, Instant.parse("2025-09-09T15:32:10Z"),
                origin, Money.of(paid, MXN), destination, Money.of(received, USD));
        return new ReadEvent("f.json", 0, eventId, command.operationDate(), true, HASH,
                new ReadEvent.Translated(command));
    }

    private static ReadEvent invalid(String eventId, boolean hasIdentity, RejectionCode code, String detail) {
        return new ReadEvent("f.json", 3, eventId, null, hasIdentity, hasIdentity ? HASH : null,
                new ReadEvent.Invalid(code, detail));
    }

    private void seed(AccountId id, CurrencyCode currency, String balance) {
        accounts.rows.put(id, Account.client(id, currency, AccountStatus.ACTIVE, Money.of(balance, currency)));
    }

    @Test
    void appliedEventIsPublishedAfterTheCommit() {
        seed(ORIGIN, MXN, "200000.00");
        seed(DESTINATION, USD, "0.00");

        SettlementOutcome outcome = service(false).post(translated("e1", ORIGIN, "15000.50", DESTINATION, "880.25"), LOAD);

        JournalEntry entry = ((Applied) outcome).entry();
        assertThat(timeline).containsExactly("begin", "commit", "publish");
        assertThat(published).containsExactly(new EntriesPosted("e1", entry.id(), entry.lines()));
        assertThat(decisions.stored).containsExactly(SettlementDecision.applied("e1", entry.id()));
        assertThat(decisions.hashes).containsExactly(HASH);
        assertThat(decisions.loads).containsExactly(LOAD);
        assertThat(journal.inserted).containsExactly(entry);
        assertThat(accounts.updated).extracting(Account::id).containsExactly(ORIGIN, DESTINATION);
        assertThat(accounts.lockRequests).containsExactly(List.of(ORIGIN, DESTINATION));
    }

    @Test
    void rejectedEventStoresItsDecisionAndIsPublishedAfterTheCommit() {
        seed(ORIGIN, MXN, "100.00");
        seed(DESTINATION, USD, "0.00");

        SettlementOutcome outcome = service(false).post(translated("e2", ORIGIN, "150.00", DESTINATION, "8.80"), LOAD);

        assertThat(outcome).isEqualTo(new Rejected(RejectionCode.INSUFFICIENT_FUNDS, null, true));
        assertThat(decisions.stored).containsExactly(
                SettlementDecision.rejected("e2", RejectionCode.INSUFFICIENT_FUNDS, null));
        assertThat(timeline).containsExactly("begin", "commit", "publish");
        assertThat(published).containsExactly(new PostingRejected("e2", RejectionCode.INSUFFICIENT_FUNDS, null));
        assertThat(journal.inserted).isEmpty();
        assertThat(accounts.updated).isEmpty();
    }

    @Test
    void eventThatFailedRuleOneOrTwoStoresItsRejectionWithoutLocking() {
        SettlementOutcome outcome = service(false).post(
                invalid("e3", true, RejectionCode.INVALID_EVENT, "TOO_MANY_DECIMALS"), LOAD);

        assertThat(outcome).isEqualTo(new Rejected(RejectionCode.INVALID_EVENT, "TOO_MANY_DECIMALS", true));
        assertThat(decisions.stored).containsExactly(
                SettlementDecision.rejected("e3", RejectionCode.INVALID_EVENT, "TOO_MANY_DECIMALS"));
        assertThat(accounts.lockRequests).isEmpty();
        assertThat(published).containsExactly(new PostingRejected("e3", RejectionCode.INVALID_EVENT, "TOO_MANY_DECIMALS"));
    }

    @Test
    void eventWithoutIdentityOpensNoTransactionAndStoresNothing() {
        SettlementOutcome outcome = service(false).post(
                invalid(null, false, RejectionCode.UNSUPPORTED_EVENT_TYPE, null), LOAD);

        assertThat(outcome).isEqualTo(new Rejected(RejectionCode.UNSUPPORTED_EVENT_TYPE, null, false));
        assertThat(timeline).isEmpty();
        assertThat(decisions.stored).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void duplicateIsReportedBeforeAnyRuleWhateverItsContent() {
        SettlementDecision original = SettlementDecision.rejected("e4", RejectionCode.INSUFFICIENT_FUNDS, null);
        decisions.existing.put("e4", original);
        seed(ORIGIN, MXN, "1000.00");
        seed(DESTINATION, USD, "0.00");

        SettlementOutcome outcome = service(false).post(translated("e4", ORIGIN, "10.00", DESTINATION, "0.55"), LOAD);

        assertThat(outcome).isEqualTo(new Duplicate(original));
        assertThat(accounts.lockRequests).isEmpty();
        assertThat(decisions.stored).isEmpty();
        assertThat(journal.inserted).isEmpty();
        assertThat(published).isEmpty();
        assertThat(timeline).containsExactly("begin", "commit");
    }

    /** T05 note: the exception must leave UnitOfWork (caught inside, the transaction stays aborted). */
    @Test
    void decisionWrittenByAnotherProcessIsCaughtOutsideTheTransactionAndReportedAsDuplicate() {
        seed(ORIGIN, MXN, "1000.00");
        seed(DESTINATION, USD, "0.00");
        SettlementDecision racing = SettlementDecision.applied("e5", new EntryId(UUID.randomUUID()));
        decisions.racingWinner = racing;

        SettlementOutcome outcome = service(false).post(translated("e5", ORIGIN, "10.00", DESTINATION, "0.55"), LOAD);

        assertThat(outcome).isEqualTo(new Duplicate(racing));
        assertThat(timeline).containsExactly("begin", "rollback");
        assertThat(published).isEmpty();
    }

    /** M1: the D7 account is inserted once with its final balance and never updated. */
    @Test
    void autoCreatedAccountIsInsertedWithItsFinalBalanceAndNotUpdated() {
        seed(ORIGIN, MXN, "1000.00");
        AccountId unknown = AccountId.of("ACC555000001");

        SettlementOutcome outcome = service(true).post(translated("e6", ORIGIN, "850.00", unknown, "50.00"), LOAD);

        assertThat(outcome).isInstanceOf(Applied.class);
        assertThat(accounts.inserted).containsExactly(
                Account.client(unknown, USD, AccountStatus.ACTIVE, Money.of("50.00", USD)));
        assertThat(accounts.updated).containsExactly(
                Account.client(ORIGIN, MXN, AccountStatus.ACTIVE, Money.of("150.00", MXN)));
    }

    @Test
    void autoCreatedAccountThatAppearedMeanwhileRollsTheEventBack() {
        seed(ORIGIN, MXN, "1000.00");
        accounts.insertConflicts = true;

        assertThatThrownBy(() -> service(true).post(
                        translated("e7", ORIGIN, "850.00", AccountId.of("ACC555000001"), "50.00"), LOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACC555000001");
        assertThat(timeline).containsExactly("begin", "rollback");
        assertThat(published).isEmpty();
    }

    // --- Doubles ----------------------------------------------------------------------------

    private final class FakeUnitOfWork implements UnitOfWork {
        boolean active;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            timeline.add("begin");
            active = true;
            try {
                T result = work.get();
                timeline.add("commit");
                return result;
            } catch (RuntimeException e) {
                timeline.add("rollback");
                throw e;
            } finally {
                active = false;
            }
        }
    }

    private final class FakeAccounts implements AccountRepository {
        final Map<AccountId, Account> rows = new HashMap<>();
        final List<List<AccountId>> lockRequests = new ArrayList<>();
        final List<Account> inserted = new ArrayList<>();
        final List<Account> updated = new ArrayList<>();
        boolean insertConflicts;

        @Override
        public Optional<Account> findById(AccountId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<Account> lockClientAccounts(Collection<AccountId> ids) {
            assertThat(unitOfWork.active).as("locks are taken inside the transaction").isTrue();
            List<AccountId> sorted = ids.stream().sorted().toList();
            lockRequests.add(sorted);
            return sorted.stream().map(rows::get).filter(a -> a != null && a.isClient()).toList();
        }

        @Override
        public boolean insertIfAbsent(Account account) {
            assertThat(unitOfWork.active).isTrue();
            if (insertConflicts || rows.containsKey(account.id())) {
                return false;
            }
            rows.put(account.id(), account);
            inserted.add(account);
            return true;
        }

        @Override
        public void updateBalance(Account account) {
            assertThat(unitOfWork.active).isTrue();
            assertThat(rows).containsKey(account.id());
            rows.put(account.id(), account);
            updated.add(account);
        }

        @Override
        public Optional<Money> balanceOf(AccountId id) {
            return findById(id).flatMap(Account::balance);
        }
    }

    private final class FakeJournal implements JournalRepository {
        final List<JournalEntry> inserted = new ArrayList<>();

        @Override
        public void insert(JournalEntry entry) {
            assertThat(unitOfWork.active).isTrue();
            inserted.add(entry);
        }
    }

    private final class FakeDecisions implements SettlementDecisionRepository {
        final Map<String, SettlementDecision> existing = new HashMap<>();
        final List<SettlementDecision> stored = new ArrayList<>();
        final List<String> hashes = new ArrayList<>();
        final List<UUID> loads = new ArrayList<>();
        /** Decided by "another process" after our find and before our insert. */
        SettlementDecision racingWinner;

        @Override
        public Optional<SettlementDecision> find(String sourceEventId) {
            if (racingWinner != null && !unitOfWork.active) {
                return Optional.of(racingWinner);
            }
            return Optional.ofNullable(existing.get(sourceEventId));
        }

        @Override
        public void insert(SettlementDecision decision, String payloadHash, UUID loadId) {
            assertThat(unitOfWork.active).isTrue();
            if (racingWinner != null) {
                throw new DecisionAlreadyExistsException(decision.sourceEventId(), null);
            }
            stored.add(decision);
            hashes.add(payloadHash);
            loads.add(loadId);
        }
    }
}
