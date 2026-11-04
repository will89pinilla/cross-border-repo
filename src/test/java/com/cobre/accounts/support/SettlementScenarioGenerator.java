package com.cobre.accounts.support;

import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.PostSettlement;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>{@link #seedJson} and {@link #eventFiles} write a scenario as the seed and event files of F07;
 * {@link SequentialOracle} is its reference result.
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
        // Filtering draws no random numbers, so grouping once gives the same scenario as filtering per event.
        Map<CurrencyCode, List<Account>> byCurrency = new LinkedHashMap<>();
        for (CurrencyCode currency : CURRENCIES) {
            byCurrency.put(currency, accounts.stream().filter(a -> a.currency().equals(currency)).toList());
        }
        List<PostSettlement> settlements = new ArrayList<>(settlementCount);
        for (int i = 0; i < settlementCount; i++) {
            settlements.add(settlement(i, accounts, byCurrency));
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

    private PostSettlement settlement(int index, List<Account> accounts, Map<CurrencyCode, List<Account>> byCurrency) {
        CurrencyCode paid = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
        CurrencyCode received = paid.equals(MXN) ? USD : MXN;
        AccountId origin = pickAccount(accounts, byCurrency.get(paid), paid);
        AccountId destination = pickAccount(accounts, byCurrency.get(received), received);
        // Amounts up to 2,000.00 against balances up to 5,000.00: enough collisions to exhaust funds.
        Money paidAmount = cents(paid, 1, 200_000);
        Money receivedAmount = cents(received, 1, 200_000);
        Instant operationDate = BASE_DATE.plusSeconds(random.nextInt(86_400));
        return new PostSettlement(
                "cbmm_gen_%d_%06d".formatted(seed, index), operationDate,
                origin, paidAmount, destination, receivedAmount);
    }

    /** Mostly an existing account of the right currency; sometimes a rule 3 or rule 4 case. */
    private AccountId pickAccount(List<Account> accounts, List<Account> sameCurrency, CurrencyCode currency) {
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
        return sameCurrency.get(random.nextInt(sameCurrency.size())).id();
    }

    // --- F07 as files (spec §12.1) --------------------------------------------------------------

    /** The client accounts of the scenario as a seed file (§6.3). */
    public static byte[] seedJson(Scenario scenario) {
        StringBuilder json = new StringBuilder("{ \"accounts\": [\n");
        for (int i = 0; i < scenario.clientAccounts().size(); i++) {
            Account account = scenario.clientAccounts().get(i);
            json.append(i == 0 ? "" : ",\n").append(
                    "  { \"account_id\": \"%s\", \"currency\": \"%s\", \"status\": \"%s\", \"initial_balance\": \"%s\" }"
                            .formatted(account.id().value(), account.currency().value(), account.status(),
                                    account.balance().orElseThrow().amount().toPlainString()));
        }
        return json.append("\n] }\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The settlements of the scenario as {@code fileCount} event files, named {@code F07-<seed>-<n>.json}
     * in upload order; even files are a JSON array, odd files NDJSON (§6.2).
     *
     * <p>On top of the scenario, with their own fixed-seed random source (so the settlements stay
     * those of {@link #generate}): about 40% of the events take one of 10 shared minutes, so many
     * ties are broken by {@code event_id} (D4); about 5% are written again identical and about 3%
     * again with the same {@code event_id} and another amount (D6, {@code payload_mismatch}), each
     * copy in a random file, the same one or another.
     */
    public static Map<String, byte[]> eventFiles(Scenario scenario, int fileCount) {
        Random random = new Random(scenario.seed() * 1_000_003L + 17);
        List<List<String>> files = new ArrayList<>();
        for (int f = 0; f < fileCount; f++) {
            files.add(new ArrayList<>());
        }
        for (PostSettlement settlement : scenario.settlements()) {
            Instant date = random.nextInt(10) < 4
                    ? BASE_DATE.plusSeconds(36_000 + 60L * random.nextInt(10))
                    : settlement.operationDate();
            String event = eventJson(settlement.sourceEventId(), date, settlement.originAccountId(),
                    settlement.originAmount(), settlement.destinationAccountId(), settlement.destinationAmount());
            files.get(random.nextInt(fileCount)).add(event);
            int copy = random.nextInt(100);
            if (copy < 5) {
                files.get(random.nextInt(fileCount)).add(event);
            } else if (copy < 8) {
                Money other = settlement.originAmount().plus(Money.of("0.01", settlement.originAmount().currency()));
                files.get(random.nextInt(fileCount)).add(eventJson(settlement.sourceEventId(), date,
                        settlement.originAccountId(), other, settlement.destinationAccountId(),
                        settlement.destinationAmount()));
            }
        }
        Map<String, byte[]> named = new LinkedHashMap<>();
        for (int f = 0; f < fileCount; f++) {
            String content = f % 2 == 0
                    ? "[\n" + String.join(",\n", files.get(f)) + "\n]\n"
                    : String.join("\n", files.get(f)) + "\n";
            named.put("F07-%d-%02d.json".formatted(scenario.seed(), f), content.getBytes(StandardCharsets.UTF_8));
        }
        return named;
    }

    /** One CBMM event on a single line (§6.1), amounts as JSON numbers with their 2 decimals. */
    private static String eventJson(String eventId, Instant date, AccountId origin, Money paid,
            AccountId destination, Money received) {
        return ("{\"event_id\":\"%s\",\"event_type\":\"cross_border_money_movement\",\"operation_date\":\"%s\","
                + "\"origin\":{\"account_id\":\"%s\",\"currency\":\"%s\",\"amount\":%s},"
                + "\"destination\":{\"account_id\":\"%s\",\"currency\":\"%s\",\"amount\":%s}}")
                .formatted(eventId, date, origin.value(), paid.currency().value(), paid.amount().toPlainString(),
                        destination.value(), received.currency().value(), received.amount().toPlainString());
    }

    private Money cents(CurrencyCode currency, int minInclusive, int maxInclusive) {
        long cents = minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
        return new Money(BigDecimal.valueOf(cents, 2), currency);
    }
}
