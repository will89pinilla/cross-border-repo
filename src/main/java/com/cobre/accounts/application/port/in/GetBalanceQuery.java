package com.cobre.accounts.application.port.in;

import com.cobre.accounts.domain.model.AccountId;
import java.util.Optional;

/** Committed balance of one account (spec §8.2); it works during a load too. */
public interface GetBalanceQuery {

    /** @return empty if no account has that id (404 {@code ACCOUNT_NOT_FOUND}) */
    Optional<AccountBalance> balanceOf(AccountId id);
}
