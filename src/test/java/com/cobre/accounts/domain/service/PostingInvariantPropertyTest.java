package com.cobre.accounts.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.cobre.accounts.support.SettlementScenarioGenerator;
import com.cobre.accounts.support.SettlementScenarioGenerator.Scenario;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property (spec §12.2, domain layer): for any sequence of settlements, applied one by one with
 * {@code PostingService}, the sum of all balances per currency is 0, and every client balance equals
 * the sum of its lines and is never negative. The seed is in the test name to reproduce a failure.
 *
 * <p>Even seeds run with D7 on, odd seeds with D7 off.
 */
class PostingInvariantPropertyTest {

    private static final int ACCOUNTS = 12;
    private static final int SETTLEMENTS = 400;

    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = {1L, 2L, 7L, 42L, 1234L, 99991L, 20250909L, 20261105L})
    void sumOfBalancesPerCurrencyIsZero(long seed) {
        Scenario scenario = new SettlementScenarioGenerator(seed).generate(ACCOUNTS, SETTLEMENTS);
        AtomicLong ids = new AtomicLong();
        PostingService service = new PostingService(() -> new EntryId(new UUID(seed, ids.incrementAndGet())), seed % 2 == 0);

        Map<AccountId, Account> clients = new HashMap<>();
        List<EntryLine> lines = new ArrayList<>();
        for (Account account : scenario.clientAccounts()) {
            clients.put(account.id(), account);
            lines.addAll(openingEntry(account, new EntryId(new UUID(-seed, ids.incrementAndGet()))));
        }
        assertInvariants(clients, lines);

        int applied = 0;
        for (PostSettlement settlement : scenario.settlements()) {
            int linesBefore = lines.size();
            PostingResult result = service.post(settlement, Map.copyOf(clients));
            switch (result) {
                case PostingResult.Posted posted -> {
                    applied++;
                    lines.addAll(posted.entry().lines());
                    // D7 accounts exist only if the event is applied (spec §7.2); updated wins over created.
                    posted.createdAccounts().forEach(a -> clients.put(a.id(), a));
                    posted.updatedAccounts().forEach(a -> clients.put(a.id(), a));
                    assertThat(lines).hasSize(linesBefore + 4);
                }
                case PostingResult.Rejected rejected -> assertThat(lines).hasSize(linesBefore);
            }
            assertInvariants(clients, lines);
        }
        // The generator must exercise both paths, or the property says nothing.
        assertThat(applied).as("applied settlements").isBetween(1, SETTLEMENTS - 1);
    }

    /** D9: debit COBRE_FUNDING_<currency>, credit the client (spec §6.3). */
    private static List<EntryLine> openingEntry(Account client, EntryId id) {
        Money balance = client.balance().orElseThrow();
        if (!balance.isPositive()) {
            return List.of();
        }
        Account funding = Account.funding(client.currency(), AccountStatus.ACTIVE);
        Instant at = Instant.EPOCH;
        return JournalEntry.of(id, "opening_" + client.id(), at, List.of(
                        EntryLine.of(funding, Direction.DEBIT, balance, at),
                        EntryLine.of(client, Direction.CREDIT, balance, at)))
                .lines();
    }

    private static void assertInvariants(Map<AccountId, Account> clients, List<EntryLine> lines) {
        Map<AccountId, Money> lineSums = new HashMap<>();
        for (EntryLine line : lines) {
            lineSums.merge(line.accountId(), line.signedAmount(), Money::plus);
        }

        // Total per currency: stored CLIENT balances + Cobre balances computed from lines (D10).
        Map<CurrencyCode, Money> total = new TreeMap<>((a, b) -> a.value().compareTo(b.value()));
        for (Account client : clients.values()) {
            Money stored = client.balance().orElseThrow();
            assertThat(stored.isNegative()).as("client %s balance", client.id()).isFalse();
            assertThat(stored)
                    .as("client %s balance equals the sum of its lines", client.id())
                    .isEqualTo(lineSums.getOrDefault(client.id(), Money.zero(client.currency())));
            total.merge(stored.currency(), stored, Money::plus);
        }
        lineSums.forEach((accountId, sum) -> {
            if (accountId.isCobreReserved()) {
                total.merge(sum.currency(), sum, Money::plus);
            }
        });
        total.forEach((currency, sum) ->
                assertThat(sum).as("invariant in %s", currency).isEqualTo(Money.zero(currency)));
    }
}
