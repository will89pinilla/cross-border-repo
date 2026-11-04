package com.cobre.accounts.application.port.out;

import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.Money;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Accounts (spec §9). Writes only touch CLIENT balances; type, currency and status never change (§3.4). */
public interface AccountRepository {

    Optional<Account> findById(AccountId id);

    /**
     * {@code SELECT ... FOR UPDATE} of the CLIENT accounts among {@code ids}, in ascending
     * {@code account_id} (D12). Must run inside {@link UnitOfWork}. Ids that do not exist or are
     * Cobre accounts are not locked and not returned.
     *
     * @return the locked accounts, ascending by {@link AccountId}
     */
    List<Account> lockClientAccounts(Collection<AccountId> ids);

    /**
     * Inserts the account with its current balance (CLIENT) or without one (Cobre, D10).
     *
     * @return false if an account with that id already existed; it is left untouched
     */
    boolean insertIfAbsent(Account account);

    /** Stores the balance of an existing CLIENT account. */
    void updateBalance(Account account);

    /**
     * Stored balance for CLIENT; for Cobre accounts, the sum of their lines (CREDIT positive,
     * DEBIT negative), computed in SQL (D10, spec §9).
     */
    Optional<Money> balanceOf(AccountId id);
}
