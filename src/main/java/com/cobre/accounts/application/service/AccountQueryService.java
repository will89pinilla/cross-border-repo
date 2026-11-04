package com.cobre.accounts.application.service;

import com.cobre.accounts.application.port.in.AccountBalance;
import com.cobre.accounts.application.port.in.EntryLinePage;
import com.cobre.accounts.application.port.in.GetBalanceQuery;
import com.cobre.accounts.application.port.in.GetTransactionsQuery;
import com.cobre.accounts.application.port.out.AccountReadModel;
import com.cobre.accounts.domain.model.AccountId;
import java.util.Objects;
import java.util.Optional;

/**
 * The queries of spec §8.2 over committed state. They take no load lock, so they answer during a
 * load too; each read is one statement on its own connection, outside the load's transactions.
 */
public final class AccountQueryService implements GetBalanceQuery, GetTransactionsQuery {

    private final AccountReadModel readModel;

    public AccountQueryService(AccountReadModel readModel) {
        this.readModel = Objects.requireNonNull(readModel, "readModel");
    }

    @Override
    public Optional<AccountBalance> balanceOf(AccountId id) {
        return readModel.balanceOf(id);
    }

    @Override
    public Optional<EntryLinePage> linesOf(AccountId id, int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT || offset < 0) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIMIT + " and offset at least 0: " + limit + ", " + offset);
        }
        return readModel.linesOf(id, limit, offset);
    }
}
