package com.cobre.accounts.adapters.out.persistence;

import com.cobre.accounts.application.port.out.AccountRepository;
import com.cobre.accounts.application.port.out.JournalRepository;
import com.cobre.accounts.application.port.out.UnitOfWork;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Direction;
import com.cobre.accounts.domain.model.DomainInvariantException;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.JournalEntry;
import com.cobre.accounts.domain.model.Money;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seed of spec §6.3. Creates {@code COBRE_FX_POSITION_<currency>} and {@code COBRE_FUNDING_<currency>}
 * for each currency of {@code cbmm.currencies}, then each seed account that does not exist as CLIENT
 * with an opening entry {@code opening_<account_id>}: DEBIT funding, CREDIT the account (D9).
 *
 * <ul>
 *   <li>The whole seed is validated before anything is written; any error is a
 *       {@link SeedConfigurationException} that stops the service (a {@code COBRE_} id, a currency
 *       outside {@code cbmm.currencies}, an invalid balance, a repeated id...).
 *   <li>Everything is written in one transaction. An account is inserted with
 *       {@code ON CONFLICT DO NOTHING}, and its opening entry is written only if that insert created
 *       it: an existing account is never modified, and a restart duplicates nothing.
 *   <li>Opening entries have no SettlementDecision (they are not CBMM events), and do not go through
 *       the ACTIVE rule: BLOCKED and CLOSED accounts also get their initial balance (spec §6.3).
 * </ul>
 */
public final class SeedLoader {

    static final String OPENING_PREFIX = "opening_";
    /**
     * Fixed accounting date of every opening entry: before any CBMM event, and the same on every
     * start, so statements ordered by {@code posted_at} are reproducible. The real write time is
     * {@code journal_entry.recorded_at}.
     */
    static final Instant OPENING_POSTED_AT = Instant.EPOCH;
    private static final int MAX_ACCOUNT_ID_LENGTH = 64; // account.account_id varchar(64)

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final UnitOfWork unitOfWork;
    private final List<CurrencyCode> currencies;
    private final Supplier<EntryId> entryIds;

    public SeedLoader(AccountRepository accounts, JournalRepository journal, UnitOfWork unitOfWork,
            Set<CurrencyCode> currencies, Supplier<EntryId> entryIds) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.currencies = currencies.stream().sorted(Comparator.comparing(CurrencyCode::value)).toList();
        this.entryIds = Objects.requireNonNull(entryIds, "entryIds");
    }

    /** What this run created; both are 0 on a restart. */
    public record SeedResult(int createdClientAccounts, int openingEntries) {}

    private record SeedAccount(AccountId id, CurrencyCode currency, AccountStatus status, Money initialBalance) {}

    /**
     * @param source where the seed comes from ({@code cbmm.seed.path}), for error messages
     * @throws SeedConfigurationException if the seed is invalid; nothing is written then
     */
    public SeedResult load(InputStream seed, String source) {
        List<SeedAccount> parsed = parse(seed, source);
        return unitOfWork.inTransaction(() -> write(parsed));
    }

    private SeedResult write(List<SeedAccount> seedAccounts) {
        for (CurrencyCode currency : currencies) {
            accounts.insertIfAbsent(Account.fxPosition(currency, AccountStatus.ACTIVE));
            accounts.insertIfAbsent(Account.funding(currency, AccountStatus.ACTIVE));
        }
        int created = 0;
        int openings = 0;
        for (SeedAccount seedAccount : seedAccounts) {
            Account client = Account.client(
                    seedAccount.id(), seedAccount.currency(), seedAccount.status(), seedAccount.initialBalance());
            if (!accounts.insertIfAbsent(client)) {
                continue;
            }
            created++;
            if (seedAccount.initialBalance().isPositive()) {
                journal.insert(openingEntry(client, seedAccount.initialBalance(), OPENING_POSTED_AT));
                openings++;
            }
        }
        return new SeedResult(created, openings);
    }

    /** D9. Built from EntryLine directly: opening a balance is not a movement of the account. */
    private JournalEntry openingEntry(Account client, Money amount, Instant at) {
        Account funding = Account.funding(client.currency(), AccountStatus.ACTIVE);
        return JournalEntry.of(entryIds.get(), OPENING_PREFIX + client.id().value(), at, List.of(
                EntryLine.of(funding, Direction.DEBIT, amount, at),
                EntryLine.of(client, Direction.CREDIT, amount, at)));
    }

    private List<SeedAccount> parse(InputStream seed, String source) {
        JsonNode root;
        try {
            root = mapper.readTree(seed);
        } catch (JacksonException e) {
            throw new SeedConfigurationException("Seed " + source + " is not valid JSON: " + e.getOriginalMessage(), e);
        }
        JsonNode list = root == null ? null : root.get("accounts");
        if (list == null || !list.isArray()) {
            throw new SeedConfigurationException("Seed " + source + " must be an object with an \"accounts\" array");
        }
        List<SeedAccount> result = new ArrayList<>(list.size());
        Set<AccountId> seen = new HashSet<>();
        int position = 0;
        for (JsonNode node : list) {
            position++;
            SeedAccount account = parseAccount(node, source, position);
            if (!seen.add(account.id())) {
                throw new SeedConfigurationException(
                        "Seed " + source + ": account_id " + account.id() + " appears more than once");
            }
            result.add(account);
        }
        return result;
    }

    private SeedAccount parseAccount(JsonNode node, String source, int position) {
        String where = "Seed " + source + ", account #" + position;
        if (node == null || !node.isObject()) {
            throw new SeedConfigurationException(where + " is not an object");
        }
        String rawId = text(node, "account_id", where);
        if (rawId.isBlank() || rawId.length() > MAX_ACCOUNT_ID_LENGTH) {
            throw new SeedConfigurationException(
                    where + ": account_id must have 1 to " + MAX_ACCOUNT_ID_LENGTH + " characters");
        }
        AccountId id = AccountId.of(rawId);
        where = "Seed " + source + ", account " + id;
        if (id.isCobreReserved()) {
            throw new SeedConfigurationException(where + ": ids with the COBRE_ prefix are reserved for Cobre"
                    + " accounts and cannot be seeded (spec §6.3)");
        }
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(text(node, "currency", where));
        } catch (DomainInvariantException e) {
            throw new SeedConfigurationException(where + ": " + e.getMessage(), e);
        }
        if (!currencies.contains(currency)) {
            throw new SeedConfigurationException(where + ": currency " + currency
                    + " is not in cbmm.currencies " + currencies + " (spec §6.3)");
        }
        String rawStatus = text(node, "status", where);
        AccountStatus status;
        try {
            status = AccountStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException e) {
            throw new SeedConfigurationException(
                    where + ": status " + rawStatus + " is not ACTIVE, BLOCKED or CLOSED", e);
        }
        return new SeedAccount(id, currency, status, initialBalance(node, currency, where));
    }

    /** Text or JSON number, read as BigDecimal and never rounded (D11); 0 or more. */
    private static Money initialBalance(JsonNode node, CurrencyCode currency, String where) {
        JsonNode value = node.get("initial_balance");
        String problem = where + ": initial_balance must be a decimal of 0 or more with at most 2 decimals";
        BigDecimal amount;
        try {
            if (value != null && value.isString()) {
                amount = new BigDecimal(value.stringValue());
            } else if (value != null && value.isNumber()) {
                amount = value.decimalValue();
            } else {
                throw new SeedConfigurationException(problem);
            }
        } catch (NumberFormatException e) {
            throw new SeedConfigurationException(problem, e);
        }
        Money money;
        try {
            money = new Money(amount, currency);
        } catch (DomainInvariantException e) {
            throw new SeedConfigurationException(problem + " (" + e.getMessage() + ")", e);
        }
        if (money.isNegative()) {
            throw new SeedConfigurationException(problem);
        }
        return money;
    }

    private static String text(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new SeedConfigurationException(where + ": " + field + " is missing or is not text");
        }
        return value.stringValue();
    }
}
