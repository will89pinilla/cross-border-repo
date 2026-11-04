package com.cobre.accounts.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.adapters.in.file.CbmmEventTranslator;
import com.cobre.accounts.adapters.in.file.EventFileReader;
import com.cobre.accounts.adapters.out.persistence.JdbcAccountReadModel;
import com.cobre.accounts.adapters.out.persistence.JdbcAccountRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcJournalRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcSettlementDecisionRepository;
import com.cobre.accounts.adapters.out.persistence.JdbcUnitOfWork;
import com.cobre.accounts.adapters.out.persistence.PostgresLoadLock;
import com.cobre.accounts.adapters.out.persistence.SeedLoader;
import com.cobre.accounts.application.port.in.LoadResult;
import com.cobre.accounts.application.service.LoadEventFilesService;
import com.cobre.accounts.application.service.PostSettlementService;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.service.PostingService;
import com.cobre.accounts.support.ApiApp;
import com.cobre.accounts.support.FreshDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AC11 on the JSON of the report: the same files with max-concurrency 1, 4 and 16 and in every upload
 * order give the same JSON tree without {@code metadata} (compared as {@code JsonNode}, so key order
 * is not checked), equal to the fixture's hand-written {@code expected*.json}. The JSON comes from
 * the same {@link LoadReport} and {@link ApiJson} the controller uses; that the HTTP body is byte for
 * byte that JSON is checked in {@code FixturesApiTest.AC11_httpBodyIsByteForByteTheApiJsonOfTheReport}.
 * No Spring: each run is a fresh database with the fixture's seed and the real adapters.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class LoadReportDeterminismTest {

    private static final Set<CurrencyCode> CURRENCIES = Set.of(CurrencyCode.of("MXN"), CurrencyCode.of("USD"));
    private static final EventFileReader READER = new EventFileReader(new CbmmEventTranslator(CURRENCIES));

    @ParameterizedTest(name = "{0}")
    @CsvSource({"F03-duplicates, expected-1.json", "F05-operation-date-order, expected.json",
        "F04-validation-rejections, expected.json", "F06-truncated-file, expected.json"})
    void AC11_reportJsonIsIdenticalWithAnyConcurrencyAndFileOrder(String fixture, String expected) {
        List<Path> files = ApiApp.loadFiles(fixture, 1);
        JsonNode expectedReport = ApiApp.readJson(ApiApp.fixture(fixture + "/" + expected));
        int runs = 0;
        for (List<Path> order : permutations(files)) {
            for (int maxConcurrency : new int[] {1, 4, 16}) {
                LoadResult result = load(fixture, order, maxConcurrency);

                JsonNode report = ApiApp.JSON.readTree(ApiJson.write(LoadReport.from(result)));

                assertThat(report.get("metadata").get("max_concurrency").asInt()).isEqualTo(maxConcurrency);
                ObjectNode withoutMetadata = (ObjectNode) report.deepCopy();
                withoutMetadata.remove("metadata");
                assertThat(withoutMetadata).as("files %s, max-concurrency %d", order, maxConcurrency)
                        .isEqualTo(expectedReport);
                runs++;
            }
        }
        assertThat(runs).isEqualTo(3 * permutations(files).size());
    }

    private static LoadResult load(String fixture, List<Path> files, int maxConcurrency) {
        DataSource dataSource = FreshDatabase.create().appDataSource();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var accounts = new JdbcAccountRepository(jdbc);
        var journal = new JdbcJournalRepository(jdbc, new JdbcTemplate(dataSource));
        var unitOfWork = new JdbcUnitOfWork(
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), d -> {}, () -> 0.0);
        Supplier<EntryId> entryIds = () -> new EntryId(UUID.randomUUID());
        try (InputStream seed = Files.newInputStream(ApiApp.fixture(fixture + "/seed.json"))) {
            new SeedLoader(accounts, journal, unitOfWork, CURRENCIES, entryIds).load(seed, "seed.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var postSettlement = new PostSettlementService(accounts, journal, new JdbcSettlementDecisionRepository(jdbc),
                unitOfWork, new PostingService(entryIds, false), e -> {});
        var service = new LoadEventFilesService(postSettlement, new PostgresLoadLock(dataSource),
                new JdbcAccountReadModel(jdbc), maxConcurrency, Clock.systemUTC(), UUID::randomUUID);
        return service.load(() -> files.stream().map(LoadReportDeterminismTest::read).toList());
    }

    private static com.cobre.accounts.application.port.in.EventFile read(Path file) {
        try {
            return READER.read(file.getFileName().toString(), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<T>> all = new ArrayList<>();
        for (T first : items) {
            List<T> rest = new ArrayList<>(items);
            rest.remove(first);
            for (List<T> tail : permutations(rest)) {
                List<T> permutation = new ArrayList<>(List.of(first));
                permutation.addAll(tail);
                all.add(permutation);
            }
        }
        return all;
    }
}
