package com.cobre.accounts.support;

import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.PostSettlement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic scenario generator with a fixed seed: N client accounts and M settlements with many
 * collisions on the same accounts (spec §12.1, F07; §12.2 domain property test).
 *
 * <p>Same seed, same scenario, on any JVM ({@link Random} is specified bit for bit). Amounts are
 * built from integer cents, never from binary floating point. Besides valid settlements it mixes in
 * the business rejections of rules 3 to 6: unknown accounts, COBRE_ ids, currency mismatches,
 * BLOCKED/CLOSED accounts and amounts above the balance.
 *
 * <p>T08 extends this class (event files for F07 and the sequential oracle); it does not copy it.
 */
public final class SettlementScenarioGenerator {

    public static final CurrencyCode MXN = CurrencyCode.of("MXN");
    public static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final List<CurrencyCode> CURRENCIES = List.of(MXN, USD);
    private static final Instant BASE_DATE = Instant.parse("2025-09-09T00:00:00Z");

    /** Client accounts as they would be seeded, and settlements in generation order. */
    public record Scenario(long seed, List<Account> clientAccounts, List<PostSettlement> settlements) {

        public Scenario {
            clientAccounts = List.copyOf(clientAccounts);
            settlements = List.copyOf(settlements);
        }
    }

    private final Random random;
    private final long seed;

    public SettlementScenarioGenerator(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    public Scenario generate(int accountCount, int settlementCount) {
        if (accountCount < 2) {
            throw new IllegalArgumentException("Need at least 2 accounts");
        }
        List<Account> accounts = new ArrayList<>(accountCount);
        for (int i = 0; i < accountCount; i++) {
            accounts.add(account(i));
        }
        List<PostSettlement> settlements = new ArrayList<>(settlementCount);
        for (int i = 0; i < settlementCount; i++) {
            settlements.add(settlement(i, accounts));
        }
        return new Scenario(seed, accounts, settlements);
    }

    private Account account(int index) {
        CurrencyCode currency = CURRENCIES.get(index % CURRENCIES.size());
        AccountStatus status = switch (random.nextInt(20)) {
            case 0 -> AccountStatus.BLOCKED;
            case 1 -> AccountStatus.CLOSED;
            default -> AccountStatus.ACTIVE;
        };
        // About one in five accounts starts empty, like ACC987654321 in the case example.
        Money balance = random.nextInt(5) == 0 ? Money.zero(currency) : cents(currency, 1, 500_000);
        return Account.client(AccountId.of("ACC%09d".formatted(index)), currency, status, balance);
    }

    private PostSettlement settlement(int index, List<Account> accounts) {
        CurrencyCode paid = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
        CurrencyCode received = paid.equals(MXN) ? USD : MXN;
        AccountId origin = pickAccount(accounts, paid);
        AccountId destination = pickAccount(accounts, received);
        // Amounts up to 2,000.00 against balances up to 5,000.00: enough collisions to exhaust funds.
        Money paidAmount = cents(paid, 1, 200_000);
        Money receivedAmount = cents(received, 1, 200_000);
        Instant operationDate = BASE_DATE.plusSeconds(random.nextInt(86_400));
        return new PostSettlement(
                "cbmm_gen_%d_%06d".formatted(seed, index), operationDate,
                origin, paidAmount, destination, receivedAmount);
    }

    /** Mostly an existing account of the right currency; sometimes a rule 3 or rule 4 case. */
    private AccountId pickAccount(List<Account> accounts, CurrencyCode currency) {
        int roll = random.nextInt(100);
        if (roll < 3) {
            return AccountId.of("ACC_UNKNOWN_%03d".formatted(random.nextInt(5)));
        }
        if (roll < 5) {
            return random.nextBoolean() ? AccountId.fxPosition(currency) : AccountId.funding(currency);
        }
        if (roll < 9) {
            return accounts.get(random.nextInt(accounts.size())).id(); // any currency: maybe a mismatch
        }
        List<Account> sameCurrency = accounts.stream().filter(a -> a.currency().equals(currency)).toList();
        return sameCurrency.get(random.nextInt(sameCurrency.size())).id();
    }

    private Money cents(CurrencyCode currency, int minInclusive, int maxInclusive) {
        long cents = minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
        return new Money(BigDecimal.valueOf(cents, 2), currency);
    }
}
