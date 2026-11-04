package com.cobre.accounts.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.accounts.adapters.in.file.CbmmEventTranslator;
import com.cobre.accounts.adapters.in.file.EventFileReader;
import com.cobre.accounts.adapters.out.persistence.JdbcAccountRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcJournalRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcSettlementDecisionRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcUnitOfWork;
import com.cobre.accounts.adapters.out.persistence.PostgresLoadLock;
import com.cobre.accounts.adapters.out.persistence.SeedLoader;
import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.LoadInProgressException;
import com.cobre.accounts.application.port.in.LoadResult;
import com.cobre.accounts.application.port.in.LoadResult.EventResult;
import com.cobre.accounts.application.port.in.PostSettlementUseCase;
import com.cobre.accounts.application.port.in.SettlementOutcome;
import com.cobre.accounts.application.port.in.SettlementOutcome.Applied;
import com.cobre.accounts.application.port.in.SettlementOutcome.Duplicate;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.DecisionType;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.service.PostingService;
import com.cobre.accounts.support.FreshDatabase;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The load (spec §7.3 to §7.5) against PostgreSQL 18 as {@code accounts_app}, with the files of
 * each fixture read by the real reader. Every load scenario starts from an empty database with the
 * fixture's seed, so totals are exact.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LoadEventFilesServiceIntegrationTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Set<CurrencyCode> CURRENCIES = Set.of(MXN, USD);
    private static final AccountId CASE_ORIGIN = AccountId.of("ACC123456789");
    private static final AccountId CASE_DESTINATION = AccountId.of("ACC987654321");
    private static final AccountId FX_MXN = AccountId.fxPosition(MXN);
    private static final AccountId FX_USD = AccountId.fxPosition(USD);
    private static final EventFileReader READER = new EventFileReader(new CbmmEventTranslator(CURRENCIES));

    /** One database with a seed, and the real adapters on it. */
    private static final class Ledger {
        final DataSource dataSource;
        final JdbcClient jdbc;
        final JdbcAccountRepository accounts;
        final PostSettlementService postSettlement;

        Ledger(byte[] seed) {
            dataSource = FreshDatabase.create().appDataSource();
            jdbc = JdbcClient.create(dataSource);
            accounts = new JdbcAccountRepository(jdbc);
            var journal = new JdbcJournalRepository(jdbc, new JdbcTemplate(dataSource));
            var unitOfWork = new JdbcUnitOfWork(
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)), d -> {}, () -> 0.0);
            Supplier<EntryId> entryIds = () -> new EntryId(UUID.randomUUID());
            new SeedLoader(accounts, journal, unitOfWork, CURRENCIES, entryIds)
                    .load(new ByteArrayInputStream(seed), "seed.json");
            postSettlement = new PostSettlementService(accounts, journal,
                    new JdbcSettlementDecisionRepository(jdbc), unitOfWork, new PostingService(entryIds, false), e -> {});
        }

        LoadEventFilesService loads(int maxConcurrency) {
            return loads(maxConcurrency, postSettlement);
        }

        LoadEventFilesService loads(int maxConcurrency, PostSettlementUseCase posting) {
            return new LoadEventFilesService(posting, new PostgresLoadLock(dataSource), maxConcurrency,
                    Clock.systemUTC(), UUID::randomUUID);
        }

        Money balance(AccountId id) {
            return accounts.balanceOf(id).orElseThrow(() -> new AssertionError("No account " + id));
        }

        int count(String table) {
            return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
        }

        /** AC12 on the database: each currency nets to 0, and each CLIENT balance equals its lines. */
        void assertConsistent() {
            List<Map<String, Object>> perCurrency = jdbc.sql("""
                            SELECT currency, SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
                            FROM entry_line GROUP BY currency""").query().listOfRows();
            assertThat(perCurrency).allSatisfy(row ->
                    assertThat((BigDecimal) row.get("net")).as("net of " + row.get("currency")).isEqualByComparingTo("0"));
            List<String> drift = jdbc.sql("""
                            SELECT a.account_id FROM account a
                            WHERE a.type = 'CLIENT' AND a.balance <> COALESCE((
                                SELECT SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END)
                                FROM entry_line l WHERE l.account_id = a.account_id), 0)""")
                    .query(String.class).list();
            assertThat(drift).as("CLIENT balances that differ from their lines").isEmpty();
        }

        /**
         * Everything a report or a query could show, without the random entry ids: balances, lines
         * in the order of §8.1 and decisions.
         */
        List<String> state() {
            List<String> state = new ArrayList<>();
            state.addAll(jdbc.sql("""
                            SELECT account_id || ' ' || type || ' ' || COALESCE(balance::text, 'sum') FROM account
                            ORDER BY account_id COLLATE "C\"""").query(String.class).list());
            state.addAll(jdbc.sql("""
                            SELECT l.account_id || ' ' || e.source_event_id || ' ' || l.line_no || ' ' || l.direction
                                   || ' ' || l.amount || ' ' || l.posted_at
                            FROM entry_line l JOIN journal_entry e ON e.entry_id = l.entry_id
                            ORDER BY l.account_id COLLATE "C", l.posted_at, e.source_event_id COLLATE "C", l.line_no""")
                    .query(String.class).list());
            state.addAll(jdbc.sql("""
                            SELECT source_event_id || ' ' || decision || ' ' || COALESCE(reason_code, '-') || ' '
                                   || COALESCE(reason_detail, '-') || ' ' || payload_hash
                            FROM settlement_decision ORDER BY source_event_id COLLATE "C\"""")
                    .query(String.class).list());
            for (String account : jdbc.sql("SELECT account_id FROM account ORDER BY account_id COLLATE \"C\"")
                    .query(String.class).list()) {
                state.add(account + " = " + balance(AccountId.of(account)));
            }
            return state;
        }
    }

    // --- Fixture helpers ----------------------------------------------------------------------

    private static byte[] resource(String path) {
        try (InputStream in = LoadEventFilesServiceIntegrationTest.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Ledger given(String fixture) {
        return new Ledger(resource(fixture + "/seed.json"));
    }

    private static Supplier<List<EventFile>> files(String fixture, int load, String... names) {
        return () -> {
            List<EventFile> files = new ArrayList<>();
            for (String name : names) {
                files.add(READER.read(name, resource(fixture + "/loads/" + load + "/" + name)));
            }
            return files;
        };
    }

    private static Map<String, Integer> summary(LoadResult result) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("events_read", (int) result.eventsRead());
        summary.put("applied", (int) result.applied());
        summary.put("rejected", (int) result.rejected());
        summary.put("duplicates", (int) result.duplicates());
        return summary;
    }

    private static Map<String, Integer> summary(int read, int applied, int rejected, int duplicates) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("events_read", read);
        summary.put("applied", applied);
        summary.put("rejected", rejected);
        summary.put("duplicates", duplicates);
        return summary;
    }

    /** One line per event, as the report would show it (§8.1), without metadata and entry ids. */
    private static List<String> view(LoadResult result) {
        List<String> view = new ArrayList<>();
        result.files().forEach(f -> view.add("file " + f.name() + " " + f.status() + " " + f.eventsRead()));
        for (EventResult e : result.events()) {
            String outcome = switch (e.outcome()) {
                case Applied applied -> "APPLIED";
                case Rejected rejected -> "REJECTED " + rejected.code() + " " + rejected.detail() + " stored="
                        + rejected.decisionStored();
                case Duplicate duplicate -> "DUPLICATE " + duplicate.originalDecision() + " mismatch="
                        + e.payloadMismatch();
            };
            view.add(e.event().eventId() + " " + e.event().fileName() + ":" + e.event().position() + " " + outcome);
        }
        return view;
    }

    private static Money mxn(String amount) {
        return Money.of(amount, MXN);
    }

    private static Money usd(String amount) {
        return Money.of(amount, USD);
    }

    // --- AC10 -------------------------------------------------------------------------------

    @ParameterizedTest(name = "max-concurrency {0}")
    @ValueSource(ints = {1, 16})
    void AC10_eventsOfAllFilesAreAppliedInOperationDateOrder(int maxConcurrency) {
        Ledger ledger = given("F05-operation-date-order");

        LoadResult result = ledger.loads(maxConcurrency)
                .load(files("F05-operation-date-order", 1, "F05-file1.json", "F05-file2.json"));

        assertThat(view(result)).containsExactly(
                "file F05-file1.json PROCESSED 1",
                "file F05-file2.json PROCESSED 2",
                "cbmm_20250909_000502 F05-file2.json:1 APPLIED",
                "cbmm_20250909_000501 F05-file1.json:0 APPLIED",
                "cbmm_20250909_000503 F05-file2.json:0 APPLIED");
        assertThat(ledger.balance(AccountId.of("ACC100000001"))).isEqualTo(mxn("10.00"));
        assertThat(ledger.balance(AccountId.of("ACC200000001"))).isEqualTo(usd("7.70"));
        assertThat(ledger.balance(AccountId.of("ACC200000002"))).isEqualTo(usd("7.25"));
        assertThat(ledger.balance(FX_MXN)).isEqualTo(mxn("90.00"));
        assertThat(ledger.balance(FX_USD)).isEqualTo(usd("-4.95"));
        ledger.assertConsistent();
    }

    // --- AC03, AC17 -------------------------------------------------------------------------

    @Test
    void AC03_duplicateInTheSameLoadIsReportedWithTheAppliedDecisionAndChangesNothing() {
        Ledger ledger = given("F03-duplicates");

        LoadResult result = ledger.loads(16).load(files("F03-duplicates", 1, "F03-b.json", "F03-a.json"));

        assertThat(view(result)).containsExactly(
                "file F03-a.json PROCESSED 2",
                "file F03-b.json PROCESSED 2",
                "cbmm_20250909_000301 F03-a.json:0 APPLIED",
                "cbmm_20250909_000301 F03-b.json:0 DUPLICATE APPLIED mismatch=false",
                "cbmm_20250909_000302 F03-b.json:1 APPLIED",
                "cbmm_20250909_000302 F03-a.json:1 DUPLICATE APPLIED mismatch=true");
        assertThat(summary(result)).isEqualTo(summary(4, 2, 0, 2));
        assertThat(ledger.balance(CASE_ORIGIN)).isEqualTo(mxn("198800.00"));
        assertThat(ledger.balance(CASE_DESTINATION)).isEqualTo(usd("66.00"));
        assertThat(ledger.balance(FX_MXN)).isEqualTo(mxn("1200.00"));
        assertThat(ledger.balance(FX_USD)).isEqualTo(usd("-66.00"));
        assertThat(ledger.count("entry_line")).isEqualTo(10);
        assertThat(ledger.count("settlement_decision")).isEqualTo(2);
        ledger.assertConsistent();
    }

    @Test
    void AC03_duplicateInALaterLoadKeepsTheAppliedDecisionAndChangesNothing() {
        Ledger ledger = given("F03-duplicates");
        ledger.loads(4).load(files("F03-duplicates", 1, "F03-a.json", "F03-b.json"));
        List<String> before = ledger.state();

        LoadResult second = ledger.loads(4).load(files("F03-duplicates", 2, "F03-a.json", "F03-b.json"));

        assertThat(view(second)).containsExactly(
                "file F03-a.json PROCESSED 2",
                "file F03-b.json PROCESSED 2",
                "cbmm_20250909_000301 F03-a.json:0 DUPLICATE APPLIED mismatch=false",
                "cbmm_20250909_000301 F03-b.json:0 DUPLICATE APPLIED mismatch=false",
                "cbmm_20250909_000302 F03-b.json:1 DUPLICATE APPLIED mismatch=false",
                "cbmm_20250909_000302 F03-a.json:1 DUPLICATE APPLIED mismatch=true");
        assertThat(summary(second)).isEqualTo(summary(4, 0, 0, 4));
        assertThat(ledger.state()).isEqualTo(before);
        assertThat(ledger.count("entry_line")).isEqualTo(10);
    }

    @Test
    void AC17_sameEventIdWithOtherContentTheFirstByD4IsAppliedAndTheOtherIsAPayloadMismatch() {
        Ledger ledger = given("F03-duplicates");

        LoadResult result = ledger.loads(1).load(files("F03-duplicates", 1, "F03-a.json", "F03-b.json"));

        EventResult winner = result.events().get(2);
        EventResult loser = result.events().get(3);
        assertThat(winner.event().fileName()).isEqualTo("F03-b.json");
        assertThat(winner.outcome()).isInstanceOf(Applied.class);
        assertThat(winner.payloadMismatch()).isFalse();
        assertThat(loser.event().fileName()).isEqualTo("F03-a.json");
        assertThat(loser.outcome()).isInstanceOfSatisfying(Duplicate.class,
                d -> assertThat(d.originalDecision()).isEqualTo(DecisionType.APPLIED));
        assertThat(loser.payloadMismatch()).isTrue();
        // Version Y (200.00 MXN) was applied, never version X (100.00 MXN).
        assertThat(ledger.jdbc.sql("""
                        SELECT l.amount FROM entry_line l JOIN journal_entry e ON e.entry_id = l.entry_id
                        WHERE e.source_event_id = 'cbmm_20250909_000302' AND l.line_no = 1""")
                .query(BigDecimal.class).single()).isEqualByComparingTo("200.00");
        String storedHash = ledger.jdbc.sql(
                        "SELECT payload_hash FROM settlement_decision WHERE source_event_id = 'cbmm_20250909_000302'")
                .query(String.class).single();
        assertThat(storedHash).isEqualTo(winner.event().payloadHash()).isNotEqualTo(loser.event().payloadHash());
    }

    // --- AC04 -------------------------------------------------------------------------------

    @Test
    void AC04_aRejectedEventStaysRejectedWhenFundsArriveLater() {
        Ledger ledger = given("F09-rejected-stays-rejected");
        String fixture = "F09-rejected-stays-rejected";

        LoadResult first = ledger.loads(16).load(files(fixture, 1, "F09-e1.json"));
        assertThat(view(first)).containsExactly("file F09-e1.json PROCESSED 1",
                "cbmm_20250909_000901 F09-e1.json:0 REJECTED INSUFFICIENT_FUNDS null stored=true");
        assertThat(ledger.balance(CASE_ORIGIN)).isEqualTo(mxn("100.00"));

        LoadResult second = ledger.loads(16).load(files(fixture, 2, "F09-credit.json"));
        assertThat(view(second)).containsExactly("file F09-credit.json PROCESSED 1",
                "cbmm_20250909_000902 F09-credit.json:0 APPLIED");
        assertThat(ledger.balance(CASE_ORIGIN)).isEqualTo(mxn("200.00"));

        LoadResult third = ledger.loads(16).load(files(fixture, 3, "F09-e1.json"));
        assertThat(view(third)).containsExactly("file F09-e1.json PROCESSED 1",
                "cbmm_20250909_000901 F09-e1.json:0 DUPLICATE REJECTED mismatch=false");
        assertThat(summary(third)).isEqualTo(summary(1, 0, 0, 1));
        assertThat(ledger.balance(CASE_ORIGIN)).isEqualTo(mxn("200.00"));
        assertThat(ledger.balance(CASE_DESTINATION)).isEqualTo(usd("14.50"));
        assertThat(ledger.balance(FX_MXN)).isEqualTo(mxn("-100.00"));
        assertThat(ledger.balance(FX_USD)).isEqualTo(usd("5.50"));
        assertThat(ledger.count("entry_line")).isEqualTo(8);
        assertThat(ledger.jdbc.sql(
                        "SELECT reason_code FROM settlement_decision WHERE source_event_id = 'cbmm_20250909_000901'")
                .query(String.class).single()).isEqualTo("INSUFFICIENT_FUNDS");
        ledger.assertConsistent();
    }

    // --- AC16 -------------------------------------------------------------------------------

    /** A load is held inside its first event while another one arrives, here and from another instance. */
    @Test
    void AC16_aLoadWhileAnotherIsInProgressIsRefused() throws Exception {
        Ledger ledger = given("F01-case-event");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PostSettlementUseCase gated = (event, loadId) -> {
            inside.countDown();
            await(release);
            return ledger.postSettlement.post(event, loadId);
        };
        CompletableFuture<LoadResult> running = CompletableFuture.supplyAsync(
                () -> ledger.loads(4, gated).load(files("F01-case-event", 1, "F01.json")));
        assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();

        List<Boolean> readWhileRefused = new ArrayList<>();
        Supplier<List<EventFile>> secondFiles = () -> {
            readWhileRefused.add(true);
            return List.of();
        };
        // Same instance (same LoadLock adapter type, same database) and another instance.
        assertThatThrownBy(() -> ledger.loads(4).load(secondFiles)).isInstanceOf(LoadInProgressException.class);
        LoadEventFilesService otherInstance = new LoadEventFilesService(ledger.postSettlement,
                new PostgresLoadLock(ledger.dataSource), 4, Clock.systemUTC(), UUID::randomUUID);
        assertThatThrownBy(() -> otherInstance.load(secondFiles)).isInstanceOf(LoadInProgressException.class);
        assertThat(readWhileRefused).isEmpty();

        release.countDown();
        assertThat(view(running.get(30, TimeUnit.SECONDS))).contains("cbmm_20250909_000123 F01.json:0 APPLIED");
        // Once the first load is over, the next one runs.
        assertThat(view(otherInstance.load(files("F01-case-event", 1, "F01.json"))))
                .contains("cbmm_20250909_000123 F01.json:0 DUPLICATE APPLIED mismatch=false");
    }

    /** Spec §7.3: a failing event stops the load; what was decided stays, and sending again continues. */
    @Test
    void anEventThatFailsStopsTheLoadAndSendingTheFilesAgainContinuesIt() {
        Ledger ledger = given("F05-operation-date-order");
        PostSettlementUseCase failingOnE1 = (event, loadId) -> {
            if ("cbmm_20250909_000501".equals(event.eventId())) {
                throw new IllegalStateException("database is gone");
            }
            return ledger.postSettlement.post(event, loadId);
        };
        Supplier<List<EventFile>> f05 = files("F05-operation-date-order", 1, "F05-file1.json", "F05-file2.json");

        assertThatThrownBy(() -> ledger.loads(16, failingOnE1).load(f05)).hasMessage("database is gone");

        // E2 (09:00) was decided before E1; E3 depends on E1 through A and never ran.
        assertThat(ledger.count("settlement_decision")).isEqualTo(1);
        LoadResult again = ledger.loads(16).load(f05);
        assertThat(view(again)).containsExactly(
                "file F05-file1.json PROCESSED 1",
                "file F05-file2.json PROCESSED 2",
                "cbmm_20250909_000502 F05-file2.json:1 DUPLICATE APPLIED mismatch=false",
                "cbmm_20250909_000501 F05-file1.json:0 APPLIED",
                "cbmm_20250909_000503 F05-file2.json:0 APPLIED");
        assertThat(ledger.balance(AccountId.of("ACC100000001"))).isEqualTo(mxn("10.00"));
        ledger.assertConsistent();
    }

    // --- AC11 -------------------------------------------------------------------------------

    /**
     * AC11: the same files with max-concurrency 1, 4 and 16 and in every upload order, each on a
     * freshly seeded database, give the same result (without metadata) and the same database.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"F03-duplicates", "F05-operation-date-order", "F04-validation-rejections", "collisions"})
    void AC11_sameFilesGiveTheSameResultWithAnyConcurrencyAndFileOrder(String scenario) {
        byte[] seed;
        Map<String, byte[]> files;
        if (scenario.equals("collisions")) {
            seed = CollisionScenario.seed();
            files = CollisionScenario.files();
        } else {
            seed = resource(scenario + "/seed.json");
            files = fixtureFiles(scenario);
        }
        List<String> reference = null;
        for (List<String> order : permutations(new ArrayList<>(files.keySet()))) {
            for (int maxConcurrency : new int[] {1, 4, 16}) {
                Ledger ledger = new Ledger(seed);
                LoadResult result = ledger.loads(maxConcurrency).load(() -> order.stream()
                        .map(name -> READER.read(name, files.get(name)))
                        .toList());
                List<String> run = new ArrayList<>(view(result));
                run.addAll(ledger.state());
                ledger.assertConsistent();
                if (reference == null) {
                    reference = run;
                    assertThat(result.events()).isNotEmpty();
                    if (scenario.equals("collisions")) {
                        // The scenario exercises what D5 must keep in order, not only happy paths.
                        assertThat(run).anyMatch(line -> line.endsWith(" APPLIED"))
                                .anyMatch(line -> line.contains("REJECTED INSUFFICIENT_FUNDS"))
                                .anyMatch(line -> line.contains("REJECTED INVALID_EVENT TOO_MANY_DECIMALS"))
                                .anyMatch(line -> line.endsWith("DUPLICATE APPLIED mismatch=false"))
                                .anyMatch(line -> line.endsWith("mismatch=true"));
                        assertThat(result.rejected()).isGreaterThan(20);
                        assertThat(result.applied()).isGreaterThan(50);
                    }
                } else {
                    assertThat(run).as("files %s, max-concurrency %d", order, maxConcurrency).isEqualTo(reference);
                }
            }
        }
    }

    private static Map<String, byte[]> fixtureFiles(String fixture) {
        Map<String, List<String>> names = Map.of(
                "F03-duplicates", List.of("F03-a.json", "F03-b.json"),
                "F05-operation-date-order", List.of("F05-file1.json", "F05-file2.json"),
                "F04-validation-rejections", List.of("F04-invalid-events.json"));
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : names.get(fixture)) {
            files.put(name, resource(fixture + "/loads/1/" + name));
        }
        return files;
    }

    private static List<List<String>> permutations(List<String> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<String>> all = new ArrayList<>();
        for (String first : items) {
            List<String> rest = new ArrayList<>(items);
            rest.remove(first);
            for (List<String> tail : permutations(rest)) {
                List<String> permutation = new ArrayList<>(List.of(first));
                permutation.addAll(tail);
                all.add(permutation);
            }
        }
        return all;
    }

    /**
     * A small collision-heavy load with a fixed seed: 8 accounts, 3 files, 240 events with tied
     * dates, rejections by funds, identical and conflicting duplicates and a few invalid events.
     * The full F07 generator and its sequential oracle are T08.
     */
    private static final class CollisionScenario {
        private static final List<String> MXN_ACCOUNTS = List.of("ACC_M0", "ACC_M1", "ACC_M2", "ACC_M3");
        private static final List<String> USD_ACCOUNTS = List.of("ACC_U0", "ACC_U1", "ACC_U2", "ACC_U3");

        static byte[] seed() {
            Random random = new Random(7);
            String accounts = java.util.stream.Stream.concat(
                            MXN_ACCOUNTS.stream().map(id -> account(id, "MXN", random)),
                            USD_ACCOUNTS.stream().map(id -> account(id, "USD", random)))
                    .collect(Collectors.joining(",\n"));
            return ("{ \"accounts\": [\n" + accounts + "\n] }").getBytes(StandardCharsets.UTF_8);
        }

        private static String account(String id, String currency, Random random) {
            return "{ \"account_id\": \"%s\", \"currency\": \"%s\", \"status\": \"ACTIVE\", \"initial_balance\": \"%d.00\" }"
                    .formatted(id, currency, random.nextInt(500));
        }

        static Map<String, byte[]> files() {
            Random random = new Random(42);
            List<List<String>> files = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            List<String> written = new ArrayList<>();
            for (int i = 0; i < 240; i++) {
                String event;
                int kind = random.nextInt(20);
                if (kind == 0 && !written.isEmpty()) {
                    // Identical duplicate of an earlier event, maybe in another file.
                    event = written.get(random.nextInt(written.size()));
                } else if (kind == 1 && !written.isEmpty()) {
                    // Same event_id as an earlier event, other content.
                    String earlier = written.get(random.nextInt(written.size()));
                    String id = earlier.substring(earlier.indexOf("cbmm_"), earlier.indexOf("\"", earlier.indexOf("cbmm_")));
                    event = movement(id, random, "1.00");
                } else if (kind == 2) {
                    event = movement("cbmm_c_%04d".formatted(i), random, "1.005");
                } else {
                    event = movement("cbmm_c_%04d".formatted(i), random, null);
                }
                written.add(event);
                files.get(random.nextInt(3)).add(event);
            }
            Map<String, byte[]> named = new LinkedHashMap<>();
            for (int f = 0; f < files.size(); f++) {
                named.put("collisions-" + f + ".json",
                        ("[\n" + String.join(",\n", files.get(f)) + "\n]").getBytes(StandardCharsets.UTF_8));
            }
            return named;
        }

        /** @param fixedAmount null for a random amount; "1.005" makes an INVALID_EVENT / TOO_MANY_DECIMALS */
        private static String movement(String id, Random random, String fixedAmount) {
            boolean mxnOrigin = random.nextBoolean();
            String origin = (mxnOrigin ? MXN_ACCOUNTS : USD_ACCOUNTS).get(random.nextInt(4));
            String destination = (mxnOrigin ? USD_ACCOUNTS : MXN_ACCOUNTS).get(random.nextInt(4));
            String paid = fixedAmount != null ? fixedAmount : (1 + random.nextInt(150)) + "." + random.nextInt(10) + "0";
            String received = (1 + random.nextInt(20)) + ".25";
            // 12 distinct minutes for 240 events: many ties broken by event_id.
            String date = "2025-09-09T10:%02d:00Z".formatted(random.nextInt(12));
            return """
                    {"event_id": "%s", "event_type": "cross_border_money_movement", "operation_date": "%s",
                     "origin": {"account_id": "%s", "currency": "%s", "amount": %s},
                     "destination": {"account_id": "%s", "currency": "%s", "amount": %s}}"""
                    .formatted(id, date, origin, mxnOrigin ? "MXN" : "USD", paid,
                            destination, mxnOrigin ? "USD" : "MXN", received);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test to release the load");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
