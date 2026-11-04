package com.cobre.accounts.domain.service;

import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.JournalEntry;
import com.cobre.accounts.domain.model.RejectionCode;
import java.util.List;
import java.util.Objects;

/** Outcome of {@link PostingService#post}: business rejections are values, not exceptions. */
public sealed interface PostingResult {

    /**
     * @param entry the four-line entry of spec §7.1
     * @param updatedAccounts the CLIENT accounts with their new balance, ascending by account id
     *     (Cobre balances are never stored, D10)
     * @param createdAccounts accounts opened by D7 for this event, in leg order; empty with D7 off.
     *     Only an applied event creates accounts (spec §7.2). Each one is listed here as opened
     *     (CLIENT, ACTIVE, balance 0) and also in {@code updatedAccounts} with its final balance: it
     *     is inserted once, with the balance from {@code updatedAccounts}, and never updated
     */
    record Posted(JournalEntry entry, List<Account> updatedAccounts, List<Account> createdAccounts)
            implements PostingResult {

        public Posted {
            Objects.requireNonNull(entry, "entry");
            updatedAccounts = List.copyOf(updatedAccounts);
            createdAccounts = List.copyOf(createdAccounts);
        }
    }

    /**
     * A rejection has no effects: no balance, no line and no account (spec §7.2).
     *
     * @param code first failing rule among 3 to 6 (spec §7.2)
     * @param detail {@code ORIGIN} or {@code DESTINATION}; null for {@code INSUFFICIENT_FUNDS}
     */
    record Rejected(RejectionCode code, String detail) implements PostingResult {

        public Rejected {
            Objects.requireNonNull(code, "code");
        }
    }
}
