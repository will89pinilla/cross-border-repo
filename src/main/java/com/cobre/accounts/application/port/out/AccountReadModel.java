package com.cobre.accounts.application.port.out;

import com.cobre.accounts.application.port.in.AccountBalance;
import com.cobre.accounts.application.port.in.EntryLinePage;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

/**
 * Committed state for reading (spec §8.1, §8.2, §9): plain values with scale 2, never domain
 * objects. A Cobre balance is a SUM over its lines in SQL, turned into a number once (D10).
 */
public interface AccountReadModel {

    Optional<AccountBalance> balanceOf(AccountId id);

    /** Balances of the given accounts that exist; CLIENT stored, Cobre summed (D10). */
    Map<AccountId, BigDecimal> balancesOf(Collection<AccountId> ids);

    /**
     * {@code invariant} of the report: per currency, the sum of the balances of every account in
     * that currency, ordered by currency. Always 0.00 when the ledger is consistent (AC12).
     */
    SortedMap<CurrencyCode, BigDecimal> invariant();

    /** One page of the account's lines by (posted_at, source_event_id, line_no); empty if no such account. */
    Optional<EntryLinePage> linesOf(AccountId id, int limit, int offset);
}
