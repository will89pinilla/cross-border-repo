package com.cobre.accounts.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.adapters.in.file.CbmmEventTranslator;
import com.cobre.accounts.adapters.in.file.EventFileReader;
import com.cobre.accounts.adapters.out.persistence.JdbcAccountRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcJournalRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcSettlementDecisionRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcUnitOfWork;
import com.cobre.accounts.adapters.out.persistence.SeedLoader;
import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.FileStatus;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.application.port.in.SettlementOutcome;
import com.cobre.accounts.application.port.in.SettlementOutcome.Applied;
import com.cobre.accounts.application.port.in.SettlementOutcome.Duplicate;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import com.cobre.accounts.domain.event.DomainEvent;
import com.cobre.accounts.domain.event.EntriesPosted;
import com.cobre.accounts.domain.event.PostingRejected;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.AccountType;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.DecisionType;
import com.cobre.accounts.domain.model.Direction;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.model.SettlementDecision;
import com.cobre.accounts.domain.service.PostingService;
import com.cobre.accounts.support.FreshDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code PostSettlement} of one event (spec §7.3) against PostgreSQL 18 as {@code accounts_app},
 * with the seed and files of each fixture. Each test gets its own empty database, so totals are
 * exact. Events are posted one by one in file order: the load (order D4, scheduler D5) is T06.
 */
class PostSettlementServiceIntegrationTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Set<CurrencyCode> CURRENCIES = Set.of(MXN, USD);
    private static final AccountId CASE_ORIGIN = AccountId.of("ACC123456789");
    private static final AccountId CASE_DESTINATION = AccountId.of("ACC987654321");
    private static final AccountId FX_MXN = AccountId.fxPosition(MXN);
    private static final AccountId FX_USD = AccountId.fxPosition(USD);
    private static final AccountId FUNDING_MXN = AccountId.funding(MXN);
    private static final AccountId FUNDING_USD = AccountId.funding(USD);

    private final UUID loadId = UUID.randomUUID();
    private final List<DomainEvent> published = Collections.synchronizedList(new ArrayList<>());
    private final EventFileReader reader = new EventFileReader(new CbmmEventTranslator(CURRENCIES));

    private JdbcClient jdbc;
    private JdbcAccountRepository accounts;
    private JdbcSettlementDecisionRepository decisions;
    private PostSettlementService service;

    /** Empty database, the fixture's seed and the use case with D7 as given. */
    private void given(String fixture, boolean autoCreateAccounts) {
        DataSource dataSource = FreshDatabase.create().appDataSource();
        jdbc = JdbcClient.create(dataSource);
        accounts = new JdbcAccountRepository(jdbc);
        decisions = new JdbcSettlementDecisionRepository(jdbc);
        JdbcJournalRepository journal = new JdbcJournalRepository(jdbc, new JdbcTemplate(dataSource));
        JdbcUnitOfWork unitOfWork = new JdbcUnitOfWork(
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), d -> {}, () -> 0.0);
        java.util.function.Supplier<EntryId> entryIds = () -> new EntryId(UUID.randomUUID());
        try (InputStream seed = resource(fixture + "/seed.json")) {
            new SeedLoader(accounts, journal, unitOfWork, CURRENCIES, entryIds).load(seed, fixture + "/seed.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        service = new PostSettlementService(accounts, journal, decisions, unitOfWork,
                new PostingService(entryIds, autoCreateAccounts), published::add);
    }

    private List<ReadEvent> read(String fixture, String file) {
        try (InputStream in = resource(fixture + "/loads/1/" + file)) {
            EventFile read = reader.read(file, in.readAllBytes());
            assertThat(read.status()).isEqualTo(FileStatus.PROCESSED);
            return read.events();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Posts every event of the file in file order; outcomes by event id. */
    private Map<String, SettlementOutcome> postAll(String fixture, String file) {
        Map<String, SettlementOutcome> outcomes = new LinkedHashMap<>();
        for (ReadEvent event : read(fixture, file)) {
            outcomes.put(event.eventId(), service.post(event, loadId));
        }
        return outcomes;
    }

    private static InputStream resource(String path) {
        InputStream in = PostSettlementServiceIntegrationTest.class.getResourceAsStream("/fixtures/" + path);
        if (in == null) {
            throw new IllegalStateException("Missing fixture " + path);
        }
        return in;
    }

    private Money balance(AccountId id) {
        return accounts.balanceOf(id).orElseThrow(() -> new AssertionError("No account " + id));
    }

    private static Money mxn(String amount) {
        return Money.of(amount, MXN);
    }

    private static Money usd(String amount) {
        return Money.of(amount, USD);
    }

    private static Rejected rejected(RejectionCode code, String detail) {
        return new Rejected(code, detail, true);
    }

    private int linesOf(String eventId) {
        return jdbc.sql("""
                        SELECT count(*) FROM entry_line l JOIN journal_entry e ON e.entry_id = l.entry_id
                        WHERE e.source_event_id = ?""")
                .params(eventId).query(Integer.class).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    /** AC12 on the database: each currency nets to 0, and each CLIENT balance equals its lines. */
    private void assertLedgerIsConsistent() {
        List<Map<String, Object>> perCurrency = jdbc.sql("""
                        SELECT currency, SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
                        FROM entry_line GROUP BY currency""").query().listOfRows();
        assertThat(perCurrency).allSatisfy(row ->
                assertThat((BigDecimal) row.get("net")).as("net of " + row.get("currency")).isEqualByComparingTo("0"));
        List<Map<String, Object>> drift = jdbc.sql("""
                        SELECT a.account_id FROM account a
                        WHERE a.type = 'CLIENT' AND a.balance <> COALESCE((
                            SELECT SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END)
                            FROM entry_line l WHERE l.account_id = a.account_id), 0)""").query().listOfRows();
        assertThat(drift).as("CLIENT balances that differ from their lines").isEmpty();
    }

    private SettlementDecision decisionOf(String eventId) {
        return decisions.find(eventId).orElseThrow(() -> new AssertionError("No decision for " + eventId));
    }

    // --- AC01 -------------------------------------------------------------------------------

    @Test
    void AC01_caseEventAppliesFourLineEntryAndNetsToZero() {
        given("F01-case-event", false);
        ReadEvent event = read("F01-case-event", "F01.json").getFirst();

        SettlementOutcome outcome = service.post(event, loadId);

        assertThat(outcome).isInstanceOf(Applied.class);
        var entry = ((Applied) outcome).entry();
        Instant at = Instant.parse("2025-09-09T15:32:10Z");
        assertThat(entry.sourceEventId()).isEqualTo("cbmm_20250909_000123");
        assertThat(entry.postedAt()).isEqualTo(at);
        assertThat(entry.lines()).extracting(EntryLine::accountId, EntryLine::direction, EntryLine::amount, EntryLine::postedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(CASE_ORIGIN, Direction.DEBIT, mxn("15000.50"), at),
                        org.assertj.core.groups.Tuple.tuple(FX_MXN, Direction.CREDIT, mxn("15000.50"), at),
                        org.assertj.core.groups.Tuple.tuple(FX_USD, Direction.DEBIT, usd("880.25"), at),
                        org.assertj.core.groups.Tuple.tuple(CASE_DESTINATION, Direction.CREDIT, usd("880.25"), at));
        assertThat(linesOf("cbmm_20250909_000123")).isEqualTo(4);

        // Balances of spec §2.
        assertThat(balance(CASE_ORIGIN)).isEqualTo(mxn("184999.50"));
        assertThat(balance(CASE_DESTINATION)).isEqualTo(usd("880.25"));
        assertThat(balance(FX_MXN)).isEqualTo(mxn("15000.50"));
        assertThat(balance(FX_USD)).isEqualTo(usd("-880.25"));
        assertThat(balance(FUNDING_MXN)).isEqualTo(mxn("-200000.00"));
        assertThat(balance(FUNDING_USD)).isEqualTo(usd("0.00"));
        assertLedgerIsConsistent();

        assertThat(decisionOf("cbmm_20250909_000123")).isEqualTo(
                SettlementDecision.applied("cbmm_20250909_000123", entry.id()));
        assertThat(jdbc.sql("SELECT payload_hash FROM settlement_decision WHERE source_event_id = ?")
                .params("cbmm_20250909_000123").query(String.class).single()).isEqualTo(event.payloadHash());
        assertThat(jdbc.sql("SELECT load_id FROM settlement_decision WHERE source_event_id = ?")
                .params("cbmm_20250909_000123").query(UUID.class).single()).isEqualTo(loadId);
        assertThat(published).containsExactly(
                new EntriesPosted("cbmm_20250909_000123", entry.id(), entry.lines()));
    }

    // --- AC02 -------------------------------------------------------------------------------

    @Test
    void AC02_insufficientFundsLeavesBalancesAndLinesUnchanged() {
        given("F02-insufficient-funds", false);
        int linesBefore = count("entry_line");

        Map<String, SettlementOutcome> outcomes = postAll("F02-insufficient-funds", "F02.json");

        assertThat(outcomes).containsExactly(
                Map.entry("cbmm_20250909_000201", rejected(RejectionCode.INSUFFICIENT_FUNDS, null)));
        assertThat(balance(CASE_ORIGIN)).isEqualTo(mxn("100.00"));
        assertThat(balance(CASE_DESTINATION)).isEqualTo(usd("0.00"));
        assertThat(balance(FX_MXN)).isEqualTo(mxn("0.00"));
        assertThat(balance(FX_USD)).isEqualTo(usd("0.00"));
        assertThat(balance(FUNDING_MXN)).isEqualTo(mxn("-100.00"));
        assertThat(count("entry_line")).isEqualTo(linesBefore);
        assertThat(linesOf("cbmm_20250909_000201")).isZero();
        assertThat(decisionOf("cbmm_20250909_000201")).isEqualTo(
                SettlementDecision.rejected("cbmm_20250909_000201", RejectionCode.INSUFFICIENT_FUNDS, null));
        assertThat(published).containsExactly(
                new PostingRejected("cbmm_20250909_000201", RejectionCode.INSUFFICIENT_FUNDS, null));
        assertLedgerIsConsistent();
    }

    // --- AC05 to AC07 with F04 --------------------------------------------------------------

    @Test
    void AC05_currencyMismatchOnOrigin() {
        given("F04-validation-rejections", false);

        Map<String, SettlementOutcome> outcomes = postAll("F04-validation-rejections", "F04-invalid-events.json");

        assertThat(outcomes.get("cbmm_20250909_000411")).isEqualTo(rejected(RejectionCode.CURRENCY_MISMATCH, "ORIGIN"));
        assertThat(decisionOf("cbmm_20250909_000411")).isEqualTo(SettlementDecision.rejected(
                "cbmm_20250909_000411", RejectionCode.CURRENCY_MISMATCH, "ORIGIN"));
        assertThat(linesOf("cbmm_20250909_000411")).isZero();
    }

    /**
     * Spec §7.2: the same account on both legs passes rule 2 (the leg currencies differ), so rule 4
     * rejects it with CURRENCY_MISMATCH / DESTINATION, and the decision is stored (D6). Review T05:
     * the account was locked through Set.of, which throws on a repeated id.
     */
    @Test
    void AC05_sameAccountOnBothLegsIsCurrencyMismatchOnDestination() {
        given("F01-case-event", false);
        String json = new String(readBytes("F01-case-event/loads/1/F01.json"), StandardCharsets.UTF_8)
                .replace("\"ACC987654321\"", "\"ACC123456789\"");
        ReadEvent event = reader.read("same-account.json", json.getBytes(StandardCharsets.UTF_8)).events().getFirst();

        SettlementOutcome outcome = service.post(event, loadId);

        assertThat(outcome).isEqualTo(rejected(RejectionCode.CURRENCY_MISMATCH, "DESTINATION"));
        assertThat(decisionOf("cbmm_20250909_000123")).isEqualTo(SettlementDecision.rejected(
                "cbmm_20250909_000123", RejectionCode.CURRENCY_MISMATCH, "DESTINATION"));
        assertThat(linesOf("cbmm_20250909_000123")).isZero();
        assertThat(balance(CASE_ORIGIN)).isEqualTo(mxn("200000.00"));
    }

    @Test
    void AC06_unknownOriginOrDestinationAndCobreAccountsAreNotFound() {
        given("F04-validation-rejections", false);

        Map<String, SettlementOutcome> outcomes = postAll("F04-validation-rejections", "F04-invalid-events.json");

        assertThat(outcomes.get("cbmm_20250909_000412")).isEqualTo(rejected(RejectionCode.ACCOUNT_NOT_FOUND, "ORIGIN"));
        assertThat(outcomes.get("cbmm_20250909_000413"))
                .isEqualTo(rejected(RejectionCode.ACCOUNT_NOT_FOUND, "DESTINATION"));
        assertThat(outcomes.get("cbmm_20250909_000414")).isEqualTo(rejected(RejectionCode.ACCOUNT_NOT_FOUND, "ORIGIN"));
        assertThat(accounts.findById(AccountId.of("ACC404000001"))).isEmpty();
        assertThat(accounts.findById(AccountId.of("ACC404000002"))).isEmpty();
        assertThat(decisionOf("cbmm_20250909_000413")).isEqualTo(SettlementDecision.rejected(
                "cbmm_20250909_000413", RejectionCode.ACCOUNT_NOT_FOUND, "DESTINATION"));
    }

    @Test
    void AC07_blockedAccountAsOriginOrDestinationIsInactive() {
        given("F04-validation-rejections", false);

        Map<String, SettlementOutcome> outcomes = postAll("F04-validation-rejections", "F04-invalid-events.json");

        assertThat(outcomes.get("cbmm_20250909_000415")).isEqualTo(rejected(RejectionCode.ACCOUNT_INACTIVE, "ORIGIN"));
        assertThat(outcomes.get("cbmm_20250909_000416"))
                .isEqualTo(rejected(RejectionCode.ACCOUNT_INACTIVE, "DESTINATION"));
        assertThat(balance(AccountId.of("ACC555000009"))).isEqualTo(mxn("1000.00"));
        assertThat(linesOf("cbmm_20250909_000415")).isZero();
        assertThat(linesOf("cbmm_20250909_000416")).isZero();
    }

    /** The README of F04, computed by hand: only 401 and 410 move money; every rejection is stored. */
    @Test
    void AC08_fixtureF04_rulesOneToSixAndFinalBalancesOfTheReadme() {
        given("F04-validation-rejections", false);

        Map<String, SettlementOutcome> outcomes = postAll("F04-validation-rejections", "F04-invalid-events.json");

        assertThat(outcomes.get("cbmm_20250909_000401")).isInstanceOf(Applied.class);
        assertThat(outcomes.get("cbmm_20250909_000410")).isInstanceOf(Applied.class);
        assertThat(outcomes.get("cbmm_20250909_000407")).isEqualTo(rejected(RejectionCode.INVALID_EVENT, "INVALID_DATE"));
        assertThat(outcomes.get("cbmm_20250909_000409")).isEqualTo(rejected(RejectionCode.UNSUPPORTED_EVENT_TYPE, null));
        assertThat(outcomes.values()).filteredOn(Rejected.class::isInstance).hasSize(14);
        assertThat(count("settlement_decision")).isEqualTo(16);
        assertThat(decisionOf("cbmm_20250909_000404")).isEqualTo(SettlementDecision.rejected(
                "cbmm_20250909_000404", RejectionCode.INVALID_EVENT, "TOO_MANY_DECIMALS"));

        assertThat(balance(CASE_ORIGIN)).isEqualTo(mxn("199889.50"));
        assertThat(balance(CASE_DESTINATION)).isEqualTo(usd("6.08"));
        assertThat(balance(AccountId.of("ACC555000009"))).isEqualTo(mxn("1000.00"));
        assertThat(balance(FX_MXN)).isEqualTo(mxn("110.50"));
        assertThat(balance(FX_USD)).isEqualTo(usd("-6.08"));
        assertThat(balance(FUNDING_MXN)).isEqualTo(mxn("-201000.00"));
        assertLedgerIsConsistent();
    }

    // --- AC06 and AC15 with F08 -------------------------------------------------------------

    @Test
    void AC06_withAutoCreateOff_unknownAccountsAreNotFoundAndNotCreated() {
        given("F08-unknown-account", false);
        int accountsBefore = count("account");

        Map<String, SettlementOutcome> outcomes = postAll("F08-unknown-account", "F08.json");

        assertThat(outcomes).containsExactly(
                Map.entry("cbmm_20250909_000801", rejected(RejectionCode.ACCOUNT_NOT_FOUND, "DESTINATION")),
                Map.entry("cbmm_20250909_000802", rejected(RejectionCode.ACCOUNT_NOT_FOUND, "ORIGIN")));
        assertThat(count("account")).isEqualTo(accountsBefore);
        assertThat(balance(CASE_ORIGIN)).isEqualTo(mxn("200000.00"));
        assertThat(balance(FX_MXN)).isEqualTo(mxn("0.00"));
    }

    @Test
    void AC15_autoCreatedDestinationIsCreatedInUsdWith50() {
        given("F08-unknown-account", true);

        Map<String, SettlementOutcome> outcomes = postAll("F08-unknown-account", "F08.json");

        assertThat(outcomes.get("cbmm_20250909_000801")).isInstanceOf(Applied.class);
        assertThat(accounts.findById(AccountId.of("ACC555000001"))).contains(Account.client(
                AccountId.of("ACC555000001"), USD, AccountStatus.ACTIVE, usd("50.00")));
        assertThat(balance(CASE_ORIGIN)).isEqualTo(mxn("199150.00"));
        assertThat(balance(FX_MXN)).isEqualTo(mxn("850.00"));
        assertThat(balance(FX_USD)).isEqualTo(usd("-50.00"));
        assertThat(balance(CASE_DESTINATION)).isEqualTo(usd("0.00"));
        assertLedgerIsConsistent();
    }

    @Test
    void AC15_unknownOriginWithAutoCreateIsInsufficientFundsAndCreatesNothing() {
        given("F08-unknown-account", true);

        Map<String, SettlementOutcome> outcomes = postAll("F08-unknown-account", "F08.json");

        assertThat(outcomes.get("cbmm_20250909_000802")).isEqualTo(rejected(RejectionCode.INSUFFICIENT_FUNDS, null));
        assertThat(accounts.findById(AccountId.of("ACC555000002"))).isEmpty();
        assertThat(linesOf("cbmm_20250909_000802")).isZero();
        assertThat(accounts.findById(AccountId.of("ACC555000001")).map(Account::type)).contains(AccountType.CLIENT);
    }

    // --- Decision, identity and duplicates (§7.2, §7.3, D6) ----------------------------------

    @Test
    void anEventIdWithADecisionIsADuplicateAndChangesNothing() {
        given("F01-case-event", false);
        ReadEvent event = read("F01-case-event", "F01.json").getFirst();
        SettlementOutcome first = service.post(event, loadId);
        int lines = count("entry_line");
        published.clear();

        SettlementOutcome again = service.post(event, UUID.randomUUID());

        assertThat(again).isEqualTo(new Duplicate(
                SettlementDecision.applied("cbmm_20250909_000123", ((Applied) first).entry().id())));
        assertThat(count("entry_line")).isEqualTo(lines);
        assertThat(balance(CASE_ORIGIN)).isEqualTo(mxn("184999.50"));
        assertThat(published).isEmpty();
    }

    @Test
    void reservedEventIdStoresItsRejectionWithoutTouchingTheOpeningEntry() {
        given("F01-case-event", false);
        String json = new String(readBytes("F01-case-event/loads/1/F01.json"), StandardCharsets.UTF_8)
                .replace("cbmm_20250909_000123", "opening_ACC123456789");
        ReadEvent event = reader.read("reserved.json", json.getBytes(StandardCharsets.UTF_8)).events().getFirst();

        SettlementOutcome outcome = service.post(event, loadId);

        assertThat(outcome).isEqualTo(rejected(RejectionCode.INVALID_EVENT, "RESERVED_EVENT_ID"));
        assertThat(decisionOf("opening_ACC123456789")).isEqualTo(SettlementDecision.rejected(
                "opening_ACC123456789", RejectionCode.INVALID_EVENT, "RESERVED_EVENT_ID"));
        assertThat(linesOf("opening_ACC123456789")).isEqualTo(2); // the opening entry of the seed, untouched
        assertThat(balance(CASE_ORIGIN)).isEqualTo(mxn("200000.00"));
    }

    @Test
    void eventWithoutIdentityStoresNoDecision() {
        given("F01-case-event", false);
        String json = new String(readBytes("F01-case-event/loads/1/F01.json"), StandardCharsets.UTF_8)
                .replace("\"cbmm_20250909_000123\"", "\"cbmm_ñ\"");
        ReadEvent event = reader.read("no-identity.json", json.getBytes(StandardCharsets.UTF_8)).events().getFirst();

        SettlementOutcome outcome = service.post(event, loadId);

        assertThat(outcome).isEqualTo(new Rejected(RejectionCode.INVALID_EVENT, "INVALID_FORMAT", false));
        assertThat(count("settlement_decision")).isZero();
        assertThat(published).isEmpty();
    }

    /**
     * Spec §7.3: if two processes decide the same event_id, the unique keys make the second one roll
     * back and report a duplicate. Exactly one wins, whichever thread it is.
     */
    @Test
    void concurrentPostsOfTheSameEventApplyItExactlyOnce() throws Exception {
        given("F01-case-event", false);
        ReadEvent event = read("F01-case-event", "F01.json").getFirst();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SettlementOutcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.post(event, loadId);
                }));
            }
            start.countDown();
            List<SettlementOutcome> outcomes = new ArrayList<>();
            for (Future<SettlementOutcome> future : futures) {
                outcomes.add(future.get());
            }

            assertThat(outcomes).filteredOn(Applied.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(Duplicate.class::isInstance).hasSize(threads - 1)
                    .allSatisfy(o -> assertThat(((Duplicate) o).originalDecision()).isEqualTo(DecisionType.APPLIED));
        }
        assertThat(linesOf("cbmm_20250909_000123")).isEqualTo(4);
        assertThat(balance(CASE_ORIGIN)).isEqualTo(mxn("184999.50"));
        assertThat(published).hasSize(1);
        assertLedgerIsConsistent();
    }

    private static byte[] readBytes(String path) {
        try (InputStream in = resource(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
