package com.cobre.accounts.application.service;

import com.cobre.accounts.application.port.in.PostSettlementUseCase;
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
import com.cobre.accounts.application.port.out.StoredDecision;
import com.cobre.accounts.application.port.out.UnitOfWork;
import com.cobre.accounts.domain.event.EntriesPosted;
import com.cobre.accounts.domain.event.PostingRejected;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.JournalEntry;
import com.cobre.accounts.domain.model.PostSettlement;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.model.SettlementDecision;
import com.cobre.accounts.domain.service.PostingResult;
import com.cobre.accounts.domain.service.PostingResult.Posted;
import com.cobre.accounts.domain.service.PostingService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Transaction of one event (spec §7.3):
 *
 * <ol>
 *   <li>An {@code event_id} that already has a decision is a duplicate: nothing else runs (D6).
 *   <li>An event that failed rule 1 or 2 stores its REJECTED decision, without locks.
 *   <li>{@code SELECT ... FOR UPDATE} of the event's CLIENT accounts, ascending (D12).
 *   <li>Rules 3 to 6 in {@link PostingService}. If they pass: the
 *       entry, the CLIENT balances and the APPLIED decision.
 *   <li>If not: the REJECTED decision with its code and detail.
 *   <li>Commit, then {@code EntriesPosted} or {@code PostingRejected}.
 * </ol>
 *
 * <p>An event without identity (§7.2) opens no transaction and stores nothing. If another process
 * decided the same {@code event_id} first, the unique keys throw {@link DecisionAlreadyExistsException};
 * it is caught here, outside {@link UnitOfWork}, because inside it the transaction would already be
 * aborted ({@code 25P02}). The event is then a duplicate of the decision that won.
 */
public final class PostSettlementService implements PostSettlementUseCase {

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final SettlementDecisionRepository decisions;
    private final UnitOfWork unitOfWork;
    private final PostingService postingService;
    private final DomainEventPublisher publisher;

    public PostSettlementService(AccountRepository accounts, JournalRepository journal,
            SettlementDecisionRepository decisions, UnitOfWork unitOfWork, PostingService postingService,
            DomainEventPublisher publisher) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public SettlementOutcome post(ReadEvent event, UUID loadId) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(loadId, "loadId");
        if (!event.hasIdentity()) {
            // Only rules 1 and 2 can leave an event without identity; a translated one always has it.
            ReadEvent.Invalid invalid = (ReadEvent.Invalid) event.outcome();
            return new Rejected(invalid.code(), invalid.detail(), false);
        }
        SettlementOutcome outcome;
        try {
            outcome = unitOfWork.inTransaction(() -> decide(event, loadId));
        } catch (DecisionAlreadyExistsException e) {
            StoredDecision winner = decisions.find(event.eventId()).orElseThrow(() -> new IllegalStateException(
                    "Event " + event.eventId() + " conflicted with a decision that is not visible", e));
            return new Duplicate(winner.decision(), winner.payloadHash());
        }
        publishAfterCommit(event.eventId(), outcome);
        return outcome;
    }

    private SettlementOutcome decide(ReadEvent event, UUID loadId) {
        String eventId = event.eventId();
        // Step 1.
        var existing = decisions.find(eventId);
        if (existing.isPresent()) {
            return new Duplicate(existing.get().decision(), existing.get().payloadHash());
        }
        // Step 2.
        if (event.outcome() instanceof ReadEvent.Invalid invalid) {
            return reject(eventId, invalid.code(), invalid.detail(), event.payloadHash(), loadId);
        }
        PostSettlement command = ((ReadEvent.Translated) event.outcome()).command();
        // Step 3: the adapter sorts and locks only the CLIENT rows that exist. Not Set.of: origin and
        // destination may be the same account, which rule 4 rejects (spec §7.2).
        List<AccountId> ids = Stream.of(command.originAccountId(), command.destinationAccountId())
                .distinct()
                .toList();
        Map<AccountId, Account> locked = accounts
                .lockClientAccounts(ids)
                .stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
        // Steps 4 and 5.
        PostingResult result = postingService.post(command, locked);
        return switch (result) {
            case Posted posted -> apply(posted, event.payloadHash(), loadId);
            case PostingResult.Rejected rejected ->
                    reject(eventId, rejected.code(), rejected.detail(), event.payloadHash(), loadId);
        };
    }

    private Applied apply(Posted posted, String payloadHash, UUID loadId) {
        JournalEntry entry = posted.entry();
        Map<AccountId, Account> finalBalances = new HashMap<>();
        posted.updatedAccounts().forEach(account -> finalBalances.put(account.id(), account));
        Set<AccountId> created = posted.createdAccounts().stream().map(Account::id).collect(Collectors.toSet());
        // M1: an account opened by D7 is inserted once, already with its final balance. Before the
        // entry, because the FK (account_id, currency) of entry_line needs the account (spec §9).
        for (AccountId id : posted.createdAccounts().stream().map(Account::id).toList()) {
            Account account = finalBalances.get(id);
            if (!accounts.insertIfAbsent(account)) {
                // D5 serializes events by their account ids, so only another process gets here.
                throw new IllegalStateException("Account " + id + " was created by another transaction"
                        + " while event " + entry.sourceEventId() + " was opening it");
            }
        }
        journal.insert(entry);
        for (Account account : posted.updatedAccounts()) {
            if (!created.contains(account.id())) {
                accounts.updateBalance(account);
            }
        }
        decisions.insert(SettlementDecision.applied(entry.sourceEventId(), entry.id()), payloadHash, loadId);
        return new Applied(entry);
    }

    private Rejected reject(String eventId, RejectionCode code, String detail, String payloadHash, UUID loadId) {
        decisions.insert(SettlementDecision.rejected(eventId, code, detail), payloadHash, loadId);
        return new Rejected(code, detail, true);
    }

    private void publishAfterCommit(String eventId, SettlementOutcome outcome) {
        switch (outcome) {
            case Applied applied -> {
                JournalEntry entry = applied.entry();
                publisher.publish(new EntriesPosted(eventId, entry.id(), List.copyOf(entry.lines())));
            }
            case Rejected rejected -> publisher.publish(new PostingRejected(eventId, rejected.code(), rejected.detail()));
            case Duplicate duplicate -> {
                // Already decided and emitted by the load that decided it.
            }
        }
    }
}
