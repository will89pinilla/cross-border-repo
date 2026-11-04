package com.cobre.accounts.domain.service;

import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Direction;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.JournalEntry;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.PostSettlement;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.service.PostingResult.Posted;
import com.cobre.accounts.domain.service.PostingResult.Rejected;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Domain service (spec §3.4): applies rules 3 to 6 of spec §7.2, in that order, and returns the
 * entry of spec §7.1 with the new client balances, or the rejection. Never touches the database.
 *
 * <p>{@code accounts} holds the CLIENT accounts of the event that exist (already locked by the
 * application, D12). Cobre position accounts are not looked up: they are created ACTIVE by the seed
 * and never change (spec §6.3, §14), and their balance is not stored (D10), so the service uses
 * their canonical form. The {@code entry_line} FK still checks id and currency on insert.
 */
public final class PostingService {

    static final String ORIGIN = "ORIGIN";
    static final String DESTINATION = "DESTINATION";

    private final Supplier<EntryId> entryIds;
    private final boolean autoCreateAccounts;

    /**
     * @param entryIds source of entry ids, injected so the domain has no hidden randomness
     * @param autoCreateAccounts D7 ({@code cbmm.accounts.auto-create})
     */
    public PostingService(Supplier<EntryId> entryIds, boolean autoCreateAccounts) {
        this.entryIds = Objects.requireNonNull(entryIds, "entryIds");
        this.autoCreateAccounts = autoCreateAccounts;
    }

    public PostingResult post(PostSettlement command, Map<AccountId, Account> accounts) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(accounts, "accounts");
        Map<AccountId, Account> known = new HashMap<>(accounts);
        List<Account> created = new ArrayList<>();

        // Rule 3: origin, then destination.
        Account origin = resolve(command.originAccountId(), command.originAmount().currency(), known, created);
        if (origin == null) {
            return new Rejected(RejectionCode.ACCOUNT_NOT_FOUND, ORIGIN);
        }
        Account destination =
                resolve(command.destinationAccountId(), command.destinationAmount().currency(), known, created);
        if (destination == null) {
            return new Rejected(RejectionCode.ACCOUNT_NOT_FOUND, DESTINATION);
        }

        // Rule 4: origin first.
        if (!origin.currency().equals(command.originAmount().currency())) {
            return new Rejected(RejectionCode.CURRENCY_MISMATCH, ORIGIN);
        }
        if (!destination.currency().equals(command.destinationAmount().currency())) {
            return new Rejected(RejectionCode.CURRENCY_MISMATCH, DESTINATION);
        }

        // Rule 5: origin first.
        if (origin.status() != AccountStatus.ACTIVE) {
            return new Rejected(RejectionCode.ACCOUNT_INACTIVE, ORIGIN);
        }
        if (destination.status() != AccountStatus.ACTIVE) {
            return new Rejected(RejectionCode.ACCOUNT_INACTIVE, DESTINATION);
        }

        // Rule 6, checked here so that Account.debit never sees a business case.
        Money originBalance = origin.balance().orElseThrow();
        if (originBalance.compareTo(command.originAmount()) < 0) {
            return new Rejected(RejectionCode.INSUFFICIENT_FUNDS, null);
        }

        return post(command, origin, destination, created);
    }

    /**
     * Null when rule 3 fails. With D7 on, an unknown non-COBRE_ id is opened in the leg's currency;
     * it only leaves this method through {@link Posted}, since a rejected event creates no account.
     */
    private Account resolve(AccountId id, CurrencyCode legCurrency, Map<AccountId, Account> known, List<Account> created) {
        if (id.isCobreReserved()) {
            return null; // Internal to Cobre: not addressable from an event, never auto-created.
        }
        Account account = known.get(id);
        if (account != null) {
            return account.isClient() ? account : null;
        }
        if (!autoCreateAccounts) {
            return null;
        }
        Account opened = Account.client(id, legCurrency, AccountStatus.ACTIVE, Money.zero(legCurrency));
        known.put(id, opened);
        created.add(opened);
        return opened;
    }

    private Posted post(PostSettlement command, Account origin, Account destination, List<Account> created) {
        Money paid = command.originAmount();
        Money received = command.destinationAmount();
        Instant at = command.operationDate();
        Account originPosition = Account.fxPosition(paid.currency(), AccountStatus.ACTIVE);
        Account destinationPosition = Account.fxPosition(received.currency(), AccountStatus.ACTIVE);

        // Movements validate currency, status and non-negative balances (invariants, not rules).
        Account originAfter = origin.debit(paid);
        originPosition.credit(paid);
        destinationPosition.debit(received);
        Account destinationAfter = destination.credit(received);

        // Spec §7.1, lines 1 to 4.
        JournalEntry entry = JournalEntry.of(entryIds.get(), command.sourceEventId(), at, List.of(
                EntryLine.of(origin, Direction.DEBIT, paid, at),
                EntryLine.of(originPosition, Direction.CREDIT, paid, at),
                EntryLine.of(destinationPosition, Direction.DEBIT, received, at),
                EntryLine.of(destination, Direction.CREDIT, received, at)));

        List<Account> updated = new ArrayList<>(List.of(originAfter, destinationAfter));
        updated.sort(Comparator.comparing(Account::id));
        return new Posted(entry, updated, created);
    }
}
