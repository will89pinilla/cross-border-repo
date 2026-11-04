package com.cobre.accounts.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.AccountType;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.support.FreshDatabase;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seed of spec §6.3 and D9, each test on its own empty database so totals are exact (AC12).
 * Seeds are inline: each test shows the whole input it checks.
 */
class SeedLoaderTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Instant OPENING_DATE = Instant.parse("1970-01-01T00:00:00Z");

    private static final String CASE_SEED = """
            {
              "accounts": [
                { "account_id": "ACC123456789", "currency": "MXN", "status": "ACTIVE", "initial_balance": "200000.00" },
                { "account_id": "ACC987654321", "currency": "USD", "status": "ACTIVE", "initial_balance": "0.00" }
              ]
            }""";

    private JdbcClient jdbc;
    private JdbcAccountRepository accounts;
    private JdbcJournalRepository journal;
    private JdbcUnitOfWork unitOfWork;

    @BeforeEach
    void freshDatabase() {
        DataSource dataSource = FreshDatabase.create().appDataSource();
        jdbc = JdbcClient.create(dataSource);
        accounts = new JdbcAccountRepository(jdbc);
        journal = new JdbcJournalRepository(jdbc, new JdbcTemplate(dataSource));
        unitOfWork = new JdbcUnitOfWork(
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), d -> {}, () -> 0.0);
    }

    private SeedLoader loader() {
        return new SeedLoader(accounts, journal, unitOfWork, Set.of(MXN, USD), () -> new EntryId(UUID.randomUUID()));
    }

    private SeedLoader.SeedResult load(String json) {
        return loader().load(stream(json), "test-seed.json");
    }

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void createsCobreAccountsForEachConfiguredCurrency() {
        load("""
                { "accounts": [] }""");

        for (CurrencyCode currency : List.of(MXN, USD)) {
            assertThat(accounts.findById(AccountId.fxPosition(currency)))
                    .contains(Account.fxPosition(currency, AccountStatus.ACTIVE));
            assertThat(accounts.findById(AccountId.funding(currency)))
                    .contains(Account.funding(currency, AccountStatus.ACTIVE));
        }
        assertThat(count("account")).isEqualTo(4);
    }

    @Test
    void openingEntryDebitsFundingAndCreditsTheClientAccount() {
        SeedLoader.SeedResult result = load(CASE_SEED);

        assertThat(result.createdClientAccounts()).isEqualTo(2);
        assertThat(result.openingEntries()).isEqualTo(1);
        assertThat(accounts.findById(AccountId.of("ACC123456789"))).contains(Account.client(
                AccountId.of("ACC123456789"), MXN, AccountStatus.ACTIVE, Money.of("200000.00", MXN)));

        List<Map<String, Object>> lines = jdbc.sql("""
                SELECT l.line_no, l.account_id, l.direction, l.amount, l.currency, l.posted_at
                FROM journal_entry e JOIN entry_line l ON l.entry_id = e.entry_id
                WHERE e.source_event_id = 'opening_ACC123456789'
                ORDER BY l.line_no""").query().listOfRows();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("account_id", "COBRE_FUNDING_MXN").containsEntry("direction", "DEBIT");
        assertThat(lines.get(1)).containsEntry("account_id", "ACC123456789").containsEntry("direction", "CREDIT");
        assertThat(lines).allSatisfy(line -> {
            assertThat((BigDecimal) line.get("amount")).isEqualByComparingTo("200000.00");
            assertThat(line.get("currency")).isEqualTo("MXN");
            assertThat(((java.sql.Timestamp) line.get("posted_at")).toInstant()).isEqualTo(OPENING_DATE);
        });
        assertThat(jdbc.sql("SELECT posted_at FROM journal_entry WHERE source_event_id = 'opening_ACC123456789'")
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(OPENING_DATE);
        assertThat(accounts.balanceOf(AccountId.funding(MXN))).contains(Money.of("-200000.00", MXN));
    }

    @Test
    void zeroInitialBalanceHasNoOpeningEntry() {
        load(CASE_SEED);

        assertThat(accounts.findById(AccountId.of("ACC987654321")))
                .contains(Account.client(AccountId.of("ACC987654321"), USD, AccountStatus.ACTIVE, Money.zero(USD)));
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entry WHERE source_event_id = 'opening_ACC987654321'")
                .query(Integer.class).single()).isZero();
        assertThat(accounts.balanceOf(AccountId.funding(USD))).contains(Money.zero(USD));
    }

    @Test
    void openingEntriesHaveNoSettlementDecision() {
        load(CASE_SEED);

        assertThat(count("journal_entry")).isEqualTo(1);
        assertThat(count("settlement_decision")).isZero();
    }

    @Test
    void blockedAndClosedAccountsAlsoGetTheirOpeningBalance() {
        load("""
                { "accounts": [
                  { "account_id": "ACC_BLOCKED", "currency": "MXN", "status": "BLOCKED", "initial_balance": "10.50" },
                  { "account_id": "ACC_CLOSED", "currency": "USD", "status": "CLOSED", "initial_balance": 7.25 }
                ] }""");

        assertThat(accounts.findById(AccountId.of("ACC_BLOCKED"))).contains(Account.client(
                AccountId.of("ACC_BLOCKED"), MXN, AccountStatus.BLOCKED, Money.of("10.50", MXN)));
        assertThat(accounts.findById(AccountId.of("ACC_CLOSED"))).contains(Account.client(
                AccountId.of("ACC_CLOSED"), USD, AccountStatus.CLOSED, Money.of("7.25", USD)));
        assertThat(count("journal_entry")).isEqualTo(2);
    }

    /** AC12 after the seed: stored CLIENT balance = sum of its lines, and every currency nets to 0. */
    @Test
    void AC12_afterSeedClientBalancesMatchTheirLinesAndEachCurrencyNetsToZero() {
        load("""
                { "accounts": [
                  { "account_id": "ACC123456789", "currency": "MXN", "status": "ACTIVE", "initial_balance": "200000.00" },
                  { "account_id": "acc_lower", "currency": "MXN", "status": "BLOCKED", "initial_balance": "0.01" },
                  { "account_id": "ACC987654321", "currency": "USD", "status": "ACTIVE", "initial_balance": "1500.75" },
                  { "account_id": "ACC_ZERO", "currency": "USD", "status": "CLOSED", "initial_balance": "0.00" }
                ] }""");

        List<Map<String, Object>> clients = jdbc.sql("""
                SELECT a.account_id, a.balance,
                       COALESCE((SELECT SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END)
                                 FROM entry_line l WHERE l.account_id = a.account_id), 0) AS line_sum
                FROM account a WHERE a.type = 'CLIENT'""").query().listOfRows();
        assertThat(clients).hasSize(4).allSatisfy(row ->
                assertThat((BigDecimal) row.get("balance")).as("%s", row.get("account_id"))
                        .isEqualByComparingTo((BigDecimal) row.get("line_sum")));

        List<Map<String, Object>> all = jdbc.sql("SELECT account_id, currency FROM account").query().listOfRows();
        Map<String, BigDecimal> netByCurrency = all.stream().collect(Collectors.groupingBy(
                row -> (String) row.get("currency"),
                Collectors.reducing(BigDecimal.ZERO,
                        row -> accounts.balanceOf(AccountId.of((String) row.get("account_id"))).orElseThrow().amount(),
                        BigDecimal::add)));
        assertThat(netByCurrency).containsOnlyKeys("MXN", "USD");
        assertThat(netByCurrency.values()).allSatisfy(net -> assertThat(net).isEqualByComparingTo("0.00"));
    }

    @Test
    void restartingDoesNotDuplicateAnything() {
        load(CASE_SEED);
        SeedLoader.SeedResult second = load(CASE_SEED);

        assertThat(second.createdClientAccounts()).isZero();
        assertThat(second.openingEntries()).isZero();
        assertThat(count("account")).isEqualTo(6);
        assertThat(count("journal_entry")).isEqualTo(1);
        assertThat(count("entry_line")).isEqualTo(2);
        assertThat(accounts.balanceOf(AccountId.funding(MXN))).contains(Money.of("-200000.00", MXN));
    }

    @Test
    void anExistingAccountIsNotModified() {
        load(CASE_SEED);
        load("""
                { "accounts": [
                  { "account_id": "ACC123456789", "currency": "MXN", "status": "BLOCKED", "initial_balance": "5.00" }
                ] }""");

        assertThat(accounts.findById(AccountId.of("ACC123456789"))).contains(Account.client(
                AccountId.of("ACC123456789"), MXN, AccountStatus.ACTIVE, Money.of("200000.00", MXN)));
        assertThat(count("journal_entry")).isEqualTo(1);
    }

    @Test
    void cobreIdInSeedIsAConfigurationErrorAndNothingIsWritten() {
        assertThatThrownBy(() -> load("""
                { "accounts": [
                  { "account_id": "ACC_OK", "currency": "MXN", "status": "ACTIVE", "initial_balance": "1.00" },
                  { "account_id": "COBRE_FUNDING_MXN", "currency": "MXN", "status": "ACTIVE", "initial_balance": "1.00" }
                ] }"""))
                .isInstanceOf(SeedConfigurationException.class)
                .hasMessageContaining("test-seed.json")
                .hasMessageContaining("COBRE_FUNDING_MXN")
                .hasMessageContaining("COBRE_");
        assertThat(count("account")).isZero();
    }

    /** Spec §6.3: the account_id of the seed follows the printable-ASCII rule of §6.1. */
    @ParameterizedTest(name = "non printable ASCII account_id {0}")
    @ValueSource(strings = {"ACC 1", "ACC\\t1", "ACCñ", "💰", "A\\uD800", "A\\uDC00", "ACC\\u007F"})
    void nonPrintableAsciiAccountIdIsAConfigurationErrorAndNothingIsWritten(String accountId) {
        assertThatThrownBy(() -> load("""
                { "accounts": [
                  { "account_id": "ACC_OK", "currency": "MXN", "status": "ACTIVE", "initial_balance": "1.00" },
                  { "account_id": "%s", "currency": "MXN", "status": "ACTIVE", "initial_balance": "1.00" }
                ] }""".formatted(accountId)))
                .isInstanceOf(SeedConfigurationException.class)
                .hasMessageContaining("test-seed.json")
                .hasMessageContaining("printable ASCII");
        assertThat(count("account")).isZero();
    }

    @Test
    void currencyOutsideConfiguredCurrenciesIsAConfigurationError() {
        assertThatThrownBy(() -> load("""
                { "accounts": [
                  { "account_id": "ACC_EUR", "currency": "EUR", "status": "ACTIVE", "initial_balance": "1.00" }
                ] }"""))
                .isInstanceOf(SeedConfigurationException.class)
                .hasMessageContaining("ACC_EUR")
                .hasMessageContaining("EUR")
                .hasMessageContaining("cbmm.currencies");
        assertThat(count("account")).isZero();
    }

    @ParameterizedTest(name = "invalid initial_balance {0}")
    @ValueSource(strings = {"\"10.005\"", "\"-1.00\"", "\"abc\"", "\"\"", "null", "1E+999999999", "true"})
    void invalidInitialBalanceIsAConfigurationError(String balance) {
        assertThatThrownBy(() -> load("""
                { "accounts": [
                  { "account_id": "ACC_BAD", "currency": "MXN", "status": "ACTIVE", "initial_balance": %s }
                ] }""".formatted(balance)))
                .isInstanceOf(SeedConfigurationException.class)
                .hasMessageContaining("ACC_BAD")
                .hasMessageContaining("initial_balance");
        assertThat(count("account")).isZero();
    }

    @ParameterizedTest(name = "invalid seed {0}")
    @ValueSource(strings = {
        "{ \"accounts\": [ { \"account_id\": \"A\", \"currency\": \"MXN\", \"status\": \"FROZEN\", \"initial_balance\": \"1.00\" } ] }",
        "{ \"accounts\": [ { \"currency\": \"MXN\", \"status\": \"ACTIVE\", \"initial_balance\": \"1.00\" } ] }",
        "{ \"accounts\": [ { \"account_id\": \"A\", \"currency\": \"mxn\", \"status\": \"ACTIVE\", \"initial_balance\": \"1.00\" } ] }",
        "{ \"accounts\": [ { \"account_id\": \"A\", \"currency\": \"MXN\", \"status\": \"ACTIVE\", \"initial_balance\": \"1.00\" },"
                + " { \"account_id\": \"A\", \"currency\": \"MXN\", \"status\": \"ACTIVE\", \"initial_balance\": \"2.00\" } ] }",
        "{ \"accounts\": [ { \"account_id\": \"A\", \"currency\": \"MXN\", \"status\": \"ACTIVE\", \"initial_balance\": \"1.00\" }",
        "{ }",
        "[]"
    })
    void malformedSeedIsAConfigurationError(String json) {
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(SeedConfigurationException.class)
                .hasMessageContaining("test-seed.json");
        assertThat(count("account")).isZero();
    }

    @Test
    void defaultClasspathSeedIsTheCaseSeed() throws IOException {
        try (InputStream seed = getClass().getResourceAsStream("/seed/accounts.json")) {
            assertThat(seed).isNotNull();
            loader().load(seed, "classpath:seed/accounts.json");
        }

        assertThat(accounts.findById(AccountId.of("ACC123456789")).flatMap(Account::balance))
                .contains(Money.of("200000.00", MXN));
        assertThat(accounts.findById(AccountId.of("ACC987654321")).map(Account::type))
                .contains(AccountType.CLIENT);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
}
