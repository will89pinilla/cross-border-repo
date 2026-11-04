package com.cobre.accounts.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.support.ApiApp;
import com.cobre.accounts.support.ApiApp.Part;
import com.cobre.accounts.support.ApiApp.Response;
import com.cobre.accounts.support.FreshDatabase;
import com.cobre.accounts.support.SettlementScenarioGenerator;
import com.cobre.accounts.support.SettlementScenarioGenerator.Scenario;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Benchmark of spec §12.1, run with {@code ./gradlew benchmark} (never by {@code build}): F07 with
 * 1,000 accounts and 100,000 events in 10 files, uploaded by HTTP to the real service, once per
 * max-concurrency 1, 4 and 16, each on a freshly seeded database. Reports events per second (events
 * read over the wall time of the request, reading included) next to the hardware. A number is only
 * reported for a correct result: every run must satisfy AC12, and the three reports without
 * {@code metadata} must be identical (AC11).
 */
@Tag("benchmark")
class F07Benchmark {

    private static final long SEED = 20261105L;
    private static final int ACCOUNTS = 1_000;
    private static final int EVENTS = 100_000;
    private static final int FILES = 10;

    @Test
    void F07_eventsPerSecondWithMaxConcurrency1_4And16() throws IOException {
        Scenario scenario = new SettlementScenarioGenerator(SEED).generate(ACCOUNTS, EVENTS);
        Path seed = Files.createTempFile("f07-seed-", ".json");
        Files.write(seed, SettlementScenarioGenerator.seedJson(scenario));
        Map<String, byte[]> files = SettlementScenarioGenerator.eventFiles(scenario, FILES);
        List<Part> parts = files.entrySet().stream().map(f -> Part.file(f.getKey(), f.getValue())).toList();
        long bytes = files.values().stream().mapToLong(f -> f.length).sum();

        List<String> rows = new ArrayList<>();
        String reference = null;
        for (int maxConcurrency : new int[] {1, 4, 16}) {
            try (ApiApp app = ApiApp.start(FreshDatabase.create(), "file:" + seed.toAbsolutePath(), List.of(),
                    "--cbmm.processing.max-concurrency=" + maxConcurrency)) {
                long start = System.nanoTime();
                Response response = app.upload(parts, Duration.ofMinutes(30));
                double seconds = (System.nanoTime() - start) / 1e9;

                assertThat(response.status()).as(response.body()).isEqualTo(200);
                JsonNode report = response.json();
                assertAc12(app, report);
                ObjectNode copy = (ObjectNode) report.deepCopy();
                copy.remove("metadata");
                // As text: JsonNode equality would ignore the order of the properties.
                String withoutMetadata = ApiApp.JSON.writeValueAsString(copy);
                if (reference == null) {
                    reference = withoutMetadata;
                } else {
                    assertThat(withoutMetadata).as("AC11, max-concurrency %d", maxConcurrency).isEqualTo(reference);
                }
                JsonNode summary = report.get("summary");
                JsonNode metadata = report.get("metadata");
                double processing = Duration.between(Instant.parse(metadata.get("started_at").asString()),
                        Instant.parse(metadata.get("finished_at").asString())).toMillis() / 1e3;
                long read = summary.get("events_read").asLong();
                rows.add(String.format(Locale.ROOT, "| %d | %,d | %,d | %,d | %,d | %.1f | %.1f | %,.0f |",
                        maxConcurrency, read, summary.get("applied").asLong(), summary.get("rejected").asLong(),
                        summary.get("duplicates").asLong(), seconds, processing, read / seconds));
            }
        }
        BenchmarkReport.write("F07.md", String.join("\n", List.of(
                "# Benchmark F07 (spec §12.1)",
                "",
                String.format(Locale.ROOT, "Seed %d, %,d accounts, %,d settlements in %d files (%,.1f MB), by HTTP.",
                        SEED, ACCOUNTS, EVENTS, FILES, bytes / 1e6),
                "Events per second = events read / wall time of the POST (reading, processing and report).",
                "",
                "| max-concurrency | events read | applied | rejected | duplicates | request s | load s (metadata) | events/s |",
                "| --- | --- | --- | --- | --- | --- | --- | --- |",
                String.join("\n", rows),
                "",
                "AC12 checked after every run; the three reports without metadata are identical (AC11).",
                "",
                BenchmarkReport.hardware())));
    }

    /** Invariant 0.00 per currency in the report, and in the database each CLIENT balance equals its lines. */
    private static void assertAc12(ApiApp app, JsonNode report) {
        for (var currency : report.get("invariant").properties()) {
            assertThat(currency.getValue().asString()).as("invariant " + currency.getKey()).isEqualTo("0.00");
        }
        List<BigDecimal> nets = app.jdbc().sql("""
                        SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
                        FROM entry_line GROUP BY currency""").query(BigDecimal.class).list();
        assertThat(nets).allSatisfy(net -> assertThat(net).isEqualByComparingTo("0"));
        List<String> drift = app.jdbc().sql("""
                        SELECT a.account_id FROM account a
                        WHERE a.type = 'CLIENT' AND a.balance <> COALESCE((
                            SELECT SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END)
                            FROM entry_line l WHERE l.account_id = a.account_id), 0)""")
                .query(String.class).list();
        assertThat(drift).as("CLIENT balances that differ from their lines").isEmpty();
    }
}
