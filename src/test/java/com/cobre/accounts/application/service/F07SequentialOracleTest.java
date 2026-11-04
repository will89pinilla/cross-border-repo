package com.cobre.accounts.application.service;

import static com.cobre.accounts.support.SequentialOracle.duplicatesView;
import static com.cobre.accounts.support.SequentialOracle.rejectedView;
import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.adapters.in.file.CbmmEventTranslator;
import com.cobre.accounts.adapters.in.file.EventFileReader;
import com.cobre.accounts.adapters.in.rest.ApiJson;
import com.cobre.accounts.adapters.in.rest.LoadReport;
import com.cobre.accounts.adapters.out.persistence.JdbcAccountReadModel;
import com.cobre.accounts.adapters.out.persistence.JdbcAccountRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcJournalRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcSettlementDecisionRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcUnitOfWork;
import com.cobre.accounts.adapters.out.persistence.PostgresLoadLock;
import com.cobre.accounts.adapters.out.persistence.SeedLoader;
import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.LoadResult;
import com.cobre.accounts.application.port.in.LoadResult.EventResult;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.application.port.in.SettlementOutcome.Applied;
import com.cobre.accounts.application.port.in.SettlementOutcome.Duplicate;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.DecisionType;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.service.PostingService;
import com.cobre.accounts.support.ApiApp;
import com.cobre.accounts.support.FreshDatabase;
import com.cobre.accounts.support.SequentialOracle;
import com.cobre.accounts.support.SequentialOracle.Decision;
import com.cobre.accounts.support.SequentialOracle.Outcome;
import com.cobre.accounts.support.SettlementScenarioGenerator;
import com.cobre.accounts.support.SettlementScenarioGenerator.Scenario;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * F07 (spec §12.1): generated loads with a fixed seed, many collisions on the same accounts, tied
 * dates and duplicates, run by the real load against PostgreSQL 18 and compared with the
 * {@link SequentialOracle}. AC11: with max-concurrency 1, 4 and 16 and the files in upload order and
 * reversed, every run gives the oracle's outcome for every event, the oracle's {@code rejected} and
 * {@code duplicates} in the report, its balances and its decisions in the database. AC12: the
 * invariant is 0.00 per currency and every CLIENT balance equals the sum of its lines. The seed is in
 * the test name to reproduce a failure.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class F07SequentialOracleTest {

    private static final Set<CurrencyCode> CURRENCIES = Set.of(CurrencyCode.of("MXN"), CurrencyCode.of("USD"));
    private static final EventFileReader READER = new EventFileReader(new CbmmEventTranslator(CURRENCIES));
    private static final int ACCOUNTS = 50;
    private static final int SETTLEMENTS = 1_500;
    private static final int FILES = 4;

    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = {7L, 42L, 20261105L})
    void AC11_AC12_concurrentLoadMatchesTheSequentialOracle(long seed) {
        Scenario scenario = new SettlementScenarioGenerator(seed).generate(ACCOUNTS, SETTLEMENTS);
        byte[] seedJson = SettlementScenarioGenerator.seedJson(scenario);
        Map<String, byte[]> files = SettlementScenarioGenerator.eventFiles(scenario, FILES);
        List<String> names = new ArrayList<>(files.keySet());
        SequentialOracle oracle = SequentialOracle.fromSeed(seedJson, CURRENCIES);
        List<Outcome> expected = oracle.load(read(files, names));
        assertExercisesWhatD5MustKeepInOrder(expected, read(files, names));

        String referenceReport = null;
        for (List<String> order : List.of(names, names.reversed())) {
            for (int maxConcurrency : new int[] {1, 4, 16}) {
                Ledger ledger = new Ledger(seedJson);
                LoadResult result = ledger.load(maxConcurrency, read(files, order));

                String context = "files %s, max-concurrency %d".formatted(order, maxConcurrency);
                ledger.assertMatches(context, result, expected, oracle);
                // AC11 literally: the whole report without metadata, as text, is identical in every run.
                String report = reportWithoutMetadata(result);
                if (referenceReport == null) {
                    referenceReport = report;
                } else {
                    assertThat(report).as(context + ": AC11 report without metadata").isEqualTo(referenceReport);
                }
            }
        }
    }

    /** The report as the controller writes it (§8.1), without {@code metadata}, as JSON text. */
    private static String reportWithoutMetadata(LoadResult result) {
        ObjectNode report = (ObjectNode) ApiApp.JSON.readTree(ApiJson.write(LoadReport.from(result)));
        report.remove("metadata");
        return ApiApp.JSON.writeValueAsString(report);
    }

    /**
     * Two loads on the same ledger: the second sends two new files and one file of the first load
     * again (duplicates of earlier decisions, D6), and has lines dated before those of the first.
     */
    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = {7L, 42L, 20261105L})
    void AC11_AC12_severalLoadsMatchTheOracle(long seed) {
        Scenario scenario = new SettlementScenarioGenerator(seed).generate(ACCOUNTS, SETTLEMENTS);
        byte[] seedJson = SettlementScenarioGenerator.seedJson(scenario);
        Map<String, byte[]> files = SettlementScenarioGenerator.eventFiles(scenario, FILES);
        List<String> names = new ArrayList<>(files.keySet());
        List<List<String>> loads = List.of(names.subList(0, 2), List.of(names.get(2), names.get(3), names.get(0)));
        SequentialOracle oracle = SequentialOracle.fromSeed(seedJson, CURRENCIES);
        List<List<Outcome>> expected = new ArrayList<>();
        List<Map<AccountId, BigDecimal>> expectedBalances = new ArrayList<>();
        List<Map<String, Decision>> expectedDecisions = new ArrayList<>();
        for (List<String> load : loads) {
            expected.add(oracle.load(read(files, load)));
            expectedBalances.add(Map.copyOf(oracle.balances()));
            expectedDecisions.add(Map.copyOf(oracle.decisions()));
        }
        assertThat(expected.get(1)).as("the second load repeats decisions of the first")
                .anyMatch(o -> o.kind() == SequentialOracle.Kind.DUPLICATE && o.file().equals(names.get(0)));
        assertThat(earliest(read(files, loads.get(1)))).as("the second load has earlier dates")
                .isBefore(latest(read(files, loads.get(0))));

        List<String> referenceReports = new ArrayList<>();
        for (int maxConcurrency : new int[] {1, 16}) {
            Ledger ledger = new Ledger(seedJson);
            for (int load = 0; load < loads.size(); load++) {
                LoadResult result = ledger.load(maxConcurrency, read(files, loads.get(load)));

                String context = "load %d, max-concurrency %d".formatted(load + 1, maxConcurrency);
                ledger.assertMatches(context, result, expected.get(load), expectedBalances.get(load),
                        expectedDecisions.get(load));
                String report = reportWithoutMetadata(result);
                if (referenceReports.size() <= load) {
                    referenceReports.add(report);
                } else {
                    assertThat(report).as(context + ": AC11 report without metadata")
                            .isEqualTo(referenceReports.get(load));
                }
            }
        }
    }

    // --- The generated load must be worth comparing ---------------------------------------------

    private static void assertExercisesWhatD5MustKeepInOrder(List<Outcome> expected, List<EventFile> files) {
        List<String> lines = expected.stream().map(F07SequentialOracleTest::view).toList();
        assertThat(lines)
                .anyMatch(line -> line.contains(" APPLIED "))
                .anyMatch(line -> line.contains(" REJECTED INSUFFICIENT_FUNDS "))
                .anyMatch(line -> line.contains(" REJECTED ACCOUNT_NOT_FOUND "))
                .anyMatch(line -> line.contains(" REJECTED CURRENCY_MISMATCH "))
                .anyMatch(line -> line.contains(" REJECTED ACCOUNT_INACTIVE "))
                .anyMatch(line -> line.endsWith(" DUPLICATE APPLIED false"))
                .anyMatch(line -> line.endsWith(" true"));
        long insufficient = lines.stream().filter(line -> line.contains("INSUFFICIENT_FUNDS")).count();
        assertThat(insufficient).as("rejections by funds, the outcome that depends on order").isGreaterThan(50);
        List<Instant> dates = files.stream().flatMap(f -> f.events().stream()).map(ReadEvent::operationDate).toList();
        assertThat(dates.stream().distinct().count()).as("tied dates, broken by event_id")
                .isLessThan(dates.size() * 2L / 3);
    }

    private static Instant earliest(List<EventFile> files) {
        return files.stream().flatMap(f -> f.events().stream()).map(ReadEvent::operationDate)
                .min(Instant::compareTo).orElseThrow();
    }

    private static Instant latest(List<EventFile> files) {
        return files.stream().flatMap(f -> f.events().stream()).map(ReadEvent::operationDate)
                .max(Instant::compareTo).orElseThrow();
    }

    // --- Views ----------------------------------------------------------------------------------

    /** One line per event: identity, place, outcome and its fields. */
    private static String view(Outcome o) {
        return String.join(" ", String.valueOf(o.eventId()), o.file(), String.valueOf(o.position()), switch (o.kind()) {
            case APPLIED -> "APPLIED -";
            case REJECTED -> "REJECTED " + o.code() + " " + o.detail() + " " + o.decisionStored();
            case DUPLICATE -> "DUPLICATE " + o.originalDecision() + " " + o.payloadMismatch();
        });
    }

    private static String view(EventResult e) {
        ReadEvent event = e.event();
        return String.join(" ", String.valueOf(event.eventId()), event.fileName(), String.valueOf(event.position()),
                switch (e.outcome()) {
                    case Applied applied -> "APPLIED -";
                    case Rejected r -> "REJECTED " + r.code() + " " + r.detail() + " " + r.decisionStored();
                    case Duplicate d -> "DUPLICATE " + d.originalDecision() + " " + e.payloadMismatch();
                });
    }

    private static List<EventFile> read(Map<String, byte[]> files, List<String> order) {
        return order.stream().map(name -> READER.read(name, files.get(name))).toList();
    }

    // --- One seeded database with the real adapters ---------------------------------------------

    private static final class Ledger {
        final DataSource dataSource;
        final JdbcClient jdbc;
        final PostSettlementService postSettlement;

        Ledger(byte[] seed) {
            dataSource = FreshDatabase.create().appDataSource();
            jdbc = JdbcClient.create(dataSource);
            var accounts = new JdbcAccountRepository(jdbc);
            var journal = new JdbcJournalRepository(jdbc, new JdbcTemplate(dataSource));
            var unitOfWork = new JdbcUnitOfWork(
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)), d -> {}, () -> 0.0);
            Supplier<EntryId> entryIds = () -> new EntryId(UUID.randomUUID());
            new SeedLoader(accounts, journal, unitOfWork, CURRENCIES, entryIds)
                    .load(new ByteArrayInputStream(seed), "seed.json");
            postSettlement = new PostSettlementService(accounts, journal, new JdbcSettlementDecisionRepository(jdbc),
                    unitOfWork, new PostingService(entryIds, false), e -> {});
        }

        LoadResult load(int maxConcurrency, List<EventFile> files) {
            return new LoadEventFilesService(postSettlement, new PostgresLoadLock(dataSource),
                    new JdbcAccountReadModel(jdbc), maxConcurrency, Clock.systemUTC(), UUID::randomUUID)
                    .load(() -> files);
        }

        void assertMatches(String context, LoadResult result, List<Outcome> expected, SequentialOracle oracle) {
            assertMatches(context, result, expected, oracle.balances(), oracle.decisions());
        }

        void assertMatches(String context, LoadResult result, List<Outcome> expected,
                Map<AccountId, BigDecimal> balances, Map<String, Decision> decisions) {
            // Every event, in D4 order, with the oracle's outcome.
            assertThat(result.events().stream().map(F07SequentialOracleTest::view).toList()).as(context + ": events")
                    .isEqualTo(expected.stream().map(F07SequentialOracleTest::view).toList());

            // The report as the controller writes it: rejected, duplicates, final balances, invariant.
            JsonNode report = ApiApp.JSON.readTree(ApiJson.write(LoadReport.from(result)));
            assertThat(rejectedView(report)).as(context + ": report rejected").isEqualTo(rejectedView(expected));
            assertThat(duplicatesView(report)).as(context + ": report duplicates").isEqualTo(duplicatesView(expected));
            for (JsonNode account : report.get("accounts")) {
                String id = account.get("account_id").asString();
                assertThat(account.get("final_balance").asString()).as(context + ": final balance of " + id)
                        .isEqualTo(Objects.requireNonNull(balances.get(AccountId.of(id)), id).toPlainString());
            }
            for (var currency : report.get("invariant").properties()) {
                assertThat(currency.getValue().asString()).as(context + ": AC12 invariant " + currency.getKey())
                        .isEqualTo("0.00");
            }

            // The database: every balance and every decision of the oracle, and nothing else.
            Map<AccountId, BigDecimal> stored = new HashMap<>();
            new JdbcAccountReadModel(jdbc).balancesOf(balances.keySet()).forEach(stored::put);
            assertThat(stored).as(context + ": balances").isEqualTo(balances);
            assertThat(jdbc.sql("SELECT count(*) FROM account").query(Integer.class).single())
                    .as(context + ": accounts").isEqualTo(balances.size());
            Map<String, Decision> storedDecisions = new HashMap<>();
            jdbc.sql("SELECT source_event_id, decision, reason_code, reason_detail, payload_hash FROM settlement_decision")
                    .query(rs -> {
                        String code = rs.getString("reason_code");
                        storedDecisions.put(rs.getString("source_event_id"), new Decision(
                                DecisionType.valueOf(rs.getString("decision")),
                                code == null ? null : RejectionCode.valueOf(code),
                                rs.getString("reason_detail"), rs.getString("payload_hash")));
                    });
            assertThat(storedDecisions).as(context + ": decisions").isEqualTo(decisions);
            assertEntriesMatchDecisions(context, decisions);
            assertConsistent(context);
        }

        /**
         * One entry of four lines (§7.1) per APPLIED decision, linked to it, and no entry without one:
         * an orphan entry that nets to zero would pass the balance checks.
         */
        void assertEntriesMatchDecisions(String context, Map<String, Decision> decisions) {
            long applied = decisions.values().stream().filter(d -> d.decision() == DecisionType.APPLIED).count();
            assertThat(jdbc.sql("SELECT count(*) FROM journal_entry WHERE source_event_id NOT LIKE 'opening\\_%'")
                    .query(Long.class).single()).as(context + ": entries").isEqualTo(applied);
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM entry_line l JOIN journal_entry e ON e.entry_id = l.entry_id
                            WHERE e.source_event_id NOT LIKE 'opening\\_%'""")
                    .query(Long.class).single()).as(context + ": lines").isEqualTo(4 * applied);
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM journal_entry e
                            WHERE e.source_event_id NOT LIKE 'opening\\_%' AND NOT EXISTS (
                                SELECT 1 FROM settlement_decision d
                                WHERE d.entry_id = e.entry_id AND d.source_event_id = e.source_event_id
                                  AND d.decision = 'APPLIED')""")
                    .query(Long.class).single()).as(context + ": entries without their APPLIED decision").isZero();
        }

        /** AC12 on the database: each currency nets to 0, and each CLIENT balance equals its lines. */
        void assertConsistent(String context) {
            List<BigDecimal> nets = jdbc.sql("""
                            SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
                            FROM entry_line GROUP BY currency""").query(BigDecimal.class).list();
            assertThat(nets).as(context + ": AC12 net per currency").allSatisfy(net -> assertThat(net).isEqualByComparingTo("0"));
            List<String> drift = jdbc.sql("""
                            SELECT a.account_id FROM account a
                            WHERE a.type = 'CLIENT' AND a.balance <> COALESCE((
                                SELECT SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END)
                                FROM entry_line l WHERE l.account_id = a.account_id), 0)""")
                    .query(String.class).list();
            assertThat(drift).as(context + ": AC12 CLIENT balances that differ from their lines").isEmpty();
        }
    }
}
