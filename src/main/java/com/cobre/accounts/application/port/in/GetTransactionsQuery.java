package com.cobre.accounts.application.port.in;

import com.cobre.accounts.domain.model.AccountId;
import java.util.Optional;

/** Committed entry lines of one account, one page at a time (spec §8.2). */
public interface GetTransactionsQuery {

    int MAX_LIMIT = 1000;

    /**
     * @param limit between 1 and {@link #MAX_LIMIT}
     * @param offset 0 or more
     * @return empty if no account has that id (404 {@code ACCOUNT_NOT_FOUND})
     * @throws IllegalArgumentException if {@code limit} or {@code offset} is out of range
     */
    Optional<EntryLinePage> linesOf(AccountId id, int limit, int offset);
}
