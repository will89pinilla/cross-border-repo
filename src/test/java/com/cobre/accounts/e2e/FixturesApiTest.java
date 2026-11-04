package com.cobre.accounts.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.adapters.in.rest.ApiJson;
import com.cobre.accounts.adapters.in.rest.LoadReport;
import com.cobre.accounts.application.port.in.LoadEventFilesUseCase;
import com.cobre.accounts.application.port.in.LoadResult;
import com.cobre.accounts.support.ApiApp;
import com.cobre.accounts.support.ApiApp.Response;
import com.cobre.accounts.support.FreshDatabase;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.config.BeanPostProcessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spec §12.2, end to end: each fixture through the REST API against its {@code expected*.json}
 * (written by hand from the fixture's README, never from the implementation), compared without
 * {@code metadata}. After the loads, AC14: {@code /balance} and {@code /transactions} of every
 * account of the reports match them (spec §8.2).
 *
 * <p>Each test starts the service on a fresh database with the fixture's seed, so totals are exact.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class FixturesApiTest {

    @Test
    void AC01_AC14_fixtureF01_caseEventThroughTheApi() {
        runFixture("F01-case-event", List.of("expected.json"));
    }

    @Test
    void AC02_AC14_fixtureF02_insufficientFundsThroughTheApi() {
        runFixture("F02-insufficient-funds", List.of("expected.json"));
    }

    @Test
    void AC03_AC17_AC14_fixtureF03_duplicatesInTheLoadAndInASecondLoad() {
        runFixture("F03-duplicates", List.of("expected-1.json", "expected-2.json"));
    }

    @Test
    void AC05_AC06_AC07_AC08_AC09_AC14_fixtureF04_everyRejectionThroughTheApi() {
        runFixture("F04-validation-rejections", List.of("expected.json"));
    }

    @Test
    void AC10_AC14_fixtureF05_operationDateOrderAcrossFiles() {
        runFixture("F05-operation-date-order", List.of("expected.json"));
    }

    @Test
    void AC13_AC14_fixtureF06_truncatedFileIsUnreadableAndTheOtherIsApplied() {
        runFixture("F06-truncated-file", List.of("expected.json"));
    }

    @Test
    void AC06_AC14_fixtureF08_unknownAccountsWithAutoCreateOff() {
        runFixture("F08-unknown-account", List.of("expected-d7-off.json"), "--cbmm.accounts.auto-create=false");
    }

    @Test
    void AC15_AC14_fixtureF08_unknownAccountsWithAutoCreateOn() {
        runFixture("F08-unknown-account", List.of("expected-d7-on.json"), app -> {
            // 802 was rejected, so the origin it would have opened was not created (§7.2, D7).
            AccountsApiTest.assertProblem(app.get("/v1/accounts/ACC555000002/balance"), 404, "ACCOUNT_NOT_FOUND");
            AccountsApiTest.assertProblem(app.get("/v1/accounts/ACC555000002/transactions"), 404,
                    "ACCOUNT_NOT_FOUND");
        }, "--cbmm.accounts.auto-create=true");
    }

    @Test
    void AC04_AC14_fixtureF09_rejectedEventStaysRejectedInALaterLoad() {
        runFixture("F09-rejected-stays-rejected", List.of("expected-1.json", "expected-2.json", "expected-3.json"));
    }

    /**
     * {@code cbmm.currencies} reaches the translator (rule 2) and the invariant: with EUR configured,
     * an EUR leg is no longer {@code UNSUPPORTED_CURRENCY} but reaches rule 3 (the account does not
     * exist), and {@code invariant} lists EUR, MXN and USD in order (spec §6.1, §8.1).
     */
    @Test
    void AC08_AC12_currenciesComeFromConfiguration() {
        try (ApiApp app = ApiApp.start("classpath:fixtures/F01-case-event/seed.json", "--cbmm.currencies=MXN,USD,EUR")) {
            String event = """
                    [{"event_id": "cbmm_20250909_009101", "event_type": "cross_border_money_movement",
                      "operation_date": "2025-09-09T16:00:00Z",
                      "origin": {"account_id": "ACC_EUR_404", "currency": "EUR", "amount": 10.00},
                      "destination": {"account_id": "ACC987654321", "currency": "USD", "amount": 11.00}}]""";
            Response response = app.upload(List.of(ApiApp.Part.file("eur.json",
                    event.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

            assertThat(response.status()).as(response.body()).isEqualTo(200);
            assertThat(withoutMetadata(response.json())).isEqualTo(ApiApp.JSON.readTree("""
                    { "files": [ { "name": "eur.json", "status": "PROCESSED", "events_read": 1 } ],
                      "summary": { "events_read": 1, "applied": 0, "rejected": 1, "duplicates": 0 },
                      "accounts": [],
                      "rejected": [ { "event_id": "cbmm_20250909_009101", "file": "eur.json", "position": 0,
                                      "code": "ACCOUNT_NOT_FOUND", "detail": "ORIGIN", "decision_stored": true } ],
                      "duplicates": [],
                      "invariant": { "EUR": "0.00", "MXN": "0.00", "USD": "0.00" } }"""));
        }
    }

    /**
     * AC11 compares {@code ApiJson.write(LoadReport.from(result))} without Spring
     * ({@code LoadReportDeterminismTest}); this proves that is exactly what goes out through HTTP. The
     * real load use case is wrapped to keep its {@link LoadResult}; the body must be byte for byte
     * its JSON, metadata included (same result, same metadata), so no Spring mapper is involved.
     */
    @Test
    void AC11_httpBodyIsByteForByteTheApiJsonOfTheReport() {
        AtomicReference<LoadResult> captured = new AtomicReference<>();
        BeanPostProcessor capture = new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof LoadEventFilesUseCase real) {
                    return (LoadEventFilesUseCase) files -> {
                        LoadResult result = real.load(files);
                        captured.set(result);
                        return result;
                    };
                }
                return bean;
            }
        };
        try (ApiApp app = ApiApp.start(FreshDatabase.create(), "classpath:fixtures/F01-case-event/seed.json",
                builder -> builder.initializers(context -> context.getBeanFactory().addBeanPostProcessor(capture)))) {
            Response response = app.uploadLoad("F01-case-event", 1);

            assertThat(response.status()).as(response.body()).isEqualTo(200);
            assertThat(captured.get()).isNotNull();
            assertThat(response.bytes()).isEqualTo(ApiJson.write(LoadReport.from(captured.get())));
            assertThat(withoutMetadata(response.json()))
                    .isEqualTo(ApiApp.readJson(ApiApp.fixture("F01-case-event/expected.json")));
        }
    }

    /**
     * A blank file name of the multipart is {@code ""} in the report. Two blank-named files in one
     * load: F01 (1 event) and F06-valid (2 events), all applicable on the case seed. Files are listed
     * by name, then status, then events read (§8.1), so the one with 1 event goes first.
     */
    @Test
    void blankFileNamesAreEmptyInTheReport() {
        try (ApiApp app = ApiApp.start("classpath:fixtures/F01-case-event/seed.json")) {
            Response response = app.upload(List.of(
                    ApiApp.Part.file("   ", fileBytes("F06-truncated-file/loads/1/F06-valid.json")),
                    ApiApp.Part.file("", fileBytes("F01-case-event/loads/1/F01.json"))));

            assertThat(response.status()).as(response.body()).isEqualTo(200);
            JsonNode report = response.json();
            assertThat(report.get("files")).isEqualTo(ApiApp.JSON.readTree("""
                    [ { "name": "", "status": "PROCESSED", "events_read": 1 },
                      { "name": "", "status": "PROCESSED", "events_read": 2 } ]"""));
            assertThat(report.get("summary")).isEqualTo(ApiApp.JSON.readTree(
                    "{ \"events_read\": 3, \"applied\": 3, \"rejected\": 0, \"duplicates\": 0 }"));
        }
    }

    /**
     * §6.1 precision (microseconds, that of {@code timestamptz}), on the case seed: E1 with 6
     * decimals and E2 with 7 decimals ending in 0 are applied, and the report and {@code
     * /transactions} show the same {@code posted_at}; E3 with a 7th decimal other than 0 is
     * {@code INVALID_EVENT} / {@code INVALID_DATE}. Before the rule, E3 was applied and the report
     * showed {@code .1234567Z} while the database kept {@code .123457Z}.
     */
    @Test
    void AC08_AC14_postedAtIsTheSameInTheReportAndInTransactionsUpToMicroseconds() {
        String events = """
                [{"event_id": "cbmm_20250909_009201", "event_type": "cross_border_money_movement",
                  "operation_date": "2025-09-09T16:00:00.123456Z",
                  "origin": {"account_id": "ACC123456789", "currency": "MXN", "amount": 10.00},
                  "destination": {"account_id": "ACC987654321", "currency": "USD", "amount": 0.55}},
                 {"event_id": "cbmm_20250909_009202", "event_type": "cross_border_money_movement",
                  "operation_date": "2025-09-09T17:00:00.6543210Z",
                  "origin": {"account_id": "ACC123456789", "currency": "MXN", "amount": 20.00},
                  "destination": {"account_id": "ACC987654321", "currency": "USD", "amount": 1.10}},
                 {"event_id": "cbmm_20250909_009203", "event_type": "cross_border_money_movement",
                  "operation_date": "2025-09-09T18:00:00.1234567Z",
                  "origin": {"account_id": "ACC123456789", "currency": "MXN", "amount": 30.00},
                  "destination": {"account_id": "ACC987654321", "currency": "USD", "amount": 1.65}}]""";
        try (ApiApp app = ApiApp.start("classpath:fixtures/F01-case-event/seed.json")) {
            Response response = app.upload(List.of(ApiApp.Part.file("micros.json",
                    events.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

            assertThat(response.status()).as(response.body()).isEqualTo(200);
            JsonNode report = response.json();
            assertThat(report.get("rejected")).isEqualTo(ApiApp.JSON.readTree("""
                    [ { "event_id": "cbmm_20250909_009203", "file": "micros.json", "position": 2,
                        "code": "INVALID_EVENT", "detail": "INVALID_DATE", "decision_stored": true } ]"""));
            JsonNode origin = report.get("accounts").get(0);
            assertThat(origin.get("account_id").asString()).isEqualTo("ACC123456789");
            assertThat(origin.get("transactions")).extracting(line -> line.get("posted_at").asString())
                    .containsExactly("2025-09-09T16:00:00.123456Z", "2025-09-09T17:00:00.654321Z");
            JsonNode lines = app.get("/v1/accounts/ACC123456789/transactions").json().get("transactions");
            assertThat(lines).extracting(line -> line.get("posted_at").asString()).containsExactly(
                    "1970-01-01T00:00:00Z", "2025-09-09T16:00:00.123456Z", "2025-09-09T17:00:00.654321Z");
            assertQueriesMatchReports(app, List.of(report));
        }
    }

    /**
     * AC14 across loads (§8.2): two loads move the same accounts. {@code /balance} is the
     * {@code final_balance} of the second report, and {@code /transactions} without openings is the
     * lines of the first report followed by those of the second. Hand computed for ACC123456789 on
     * the case seed: 200000.00 − 100.00 (load 1, 10:00) − 50.00 (load 2, 11:00) = 199850.00.
     */
    @Test
    void AC14_queriesMatchTheUnionOfTwoReportsOnTheSameAccount() {
        String first = """
                [{"event_id": "cbmm_20250909_009301", "event_type": "cross_border_money_movement",
                  "operation_date": "2025-09-09T10:00:00Z",
                  "origin": {"account_id": "ACC123456789", "currency": "MXN", "amount": 100.00},
                  "destination": {"account_id": "ACC987654321", "currency": "USD", "amount": 5.50}}]""";
        String second = """
                [{"event_id": "cbmm_20250909_009302", "event_type": "cross_border_money_movement",
                  "operation_date": "2025-09-09T11:00:00Z",
                  "origin": {"account_id": "ACC123456789", "currency": "MXN", "amount": 50.00},
                  "destination": {"account_id": "ACC987654321", "currency": "USD", "amount": 2.75}}]""";
        try (ApiApp app = ApiApp.start("classpath:fixtures/F01-case-event/seed.json")) {
            JsonNode report1 = app.upload(List.of(ApiApp.Part.file("load1.json",
                    first.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).json();
            JsonNode report2 = app.upload(List.of(ApiApp.Part.file("load2.json",
                    second.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).json();

            assertThat(report1.get("accounts").get(0).get("final_balance").asString()).isEqualTo("199900.00");
            assertThat(report2.get("accounts").get(0).get("final_balance").asString()).isEqualTo("199850.00");
            assertThat(app.get("/v1/accounts/ACC123456789/balance").json().get("balance").asString())
                    .isEqualTo("199850.00");
            JsonNode lines = app.get("/v1/accounts/ACC123456789/transactions").json().get("transactions");
            assertThat(lines).extracting(line -> line.get("source_event_id").asString())
                    .containsExactly("opening_ACC123456789", "cbmm_20250909_009301", "cbmm_20250909_009302");
            assertQueriesMatchReports(app, List.of(report1, report2));
        }
    }

    private static byte[] fileBytes(String relativePath) {
        try {
            return java.nio.file.Files.readAllBytes(ApiApp.fixture(relativePath));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Load {@code i} of the fixture is compared with {@code expected.get(i - 1)}. */
    private static void runFixture(String fixture, List<String> expected, String... extraArgs) {
        runFixture(fixture, expected, app -> {}, extraArgs);
    }

    /** @param afterLoads extra checks on the running service once every load is done */
    private static void runFixture(String fixture, List<String> expected,
            java.util.function.Consumer<ApiApp> afterLoads, String... extraArgs) {
        try (ApiApp app = ApiApp.start("classpath:fixtures/" + fixture + "/seed.json", extraArgs)) {
            List<JsonNode> reports = new ArrayList<>();
            for (int load = 1; load <= expected.size(); load++) {
                Response response = app.uploadLoad(fixture, load);

                assertThat(response.status()).as("load %d: %s", load, response.body()).isEqualTo(200);
                assertThat(response.contentType()).startsWith("application/json");
                JsonNode report = response.json();
                assertMetadata(report);
                assertThat(withoutMetadata(report)).as("load %d of %s", load, fixture)
                        .isEqualTo(ApiApp.readJson(ApiApp.fixture(fixture + "/" + expected.get(load - 1))));
                reports.add(report);
            }
            assertQueriesMatchReports(app, reports);
            afterLoads.accept(app);
        }
    }

    private static void assertMetadata(JsonNode report) {
        JsonNode metadata = report.get("metadata");
        assertThat(metadata).as("metadata").isNotNull();
        UUID.fromString(metadata.get("load_id").asString());
        Instant started = Instant.parse(metadata.get("started_at").asString());
        Instant finished = Instant.parse(metadata.get("finished_at").asString());
        assertThat(finished).isAfterOrEqualTo(started);
        assertThat(metadata.get("max_concurrency").asInt()).isEqualTo(16);
    }

    static ObjectNode withoutMetadata(JsonNode report) {
        ObjectNode copy = (ObjectNode) report.deepCopy();
        copy.remove("metadata");
        return copy;
    }

    /**
     * AC14 as fixed in spec §8.2: {@code /balance} equals the {@code final_balance} of the last
     * report where the account appears, and the lines of {@code /transactions} without openings are,
     * in the same order, the union of that account's lines in every report.
     */
    static void assertQueriesMatchReports(ApiApp app, List<JsonNode> reports) {
        Map<String, JsonNode> lastSeen = new LinkedHashMap<>();
        Map<String, List<JsonNode>> linesByAccount = new LinkedHashMap<>();
        for (JsonNode report : reports) {
            for (JsonNode account : report.get("accounts")) {
                String id = account.get("account_id").asString();
                lastSeen.put(id, account);
                List<JsonNode> lines = linesByAccount.computeIfAbsent(id, k -> new ArrayList<>());
                account.get("transactions").forEach(lines::add);
            }
        }
        for (Map.Entry<String, JsonNode> entry : lastSeen.entrySet()) {
            String id = entry.getKey();
            JsonNode reported = entry.getValue();

            Response balance = app.get("/v1/accounts/" + id + "/balance");
            assertThat(balance.status()).as("balance of %s: %s", id, balance.body()).isEqualTo(200);
            JsonNode body = balance.json();
            assertThat(body.get("account_id").asString()).isEqualTo(id);
            assertThat(body.get("currency")).isEqualTo(reported.get("currency"));
            assertThat(body.get("balance")).as("balance of %s", id).isEqualTo(reported.get("final_balance"));
            assertThat(body.get("type").asString()).isEqualTo(id.startsWith("COBRE_FX_POSITION_") ? "COBRE_POSITION"
                    : id.startsWith("COBRE_FUNDING_") ? "COBRE_FUNDING" : "CLIENT");

            Response transactions = app.get("/v1/accounts/" + id + "/transactions?limit=1000&offset=0");
            assertThat(transactions.status()).as("transactions of %s: %s", id, transactions.body()).isEqualTo(200);
            JsonNode page = transactions.json();
            ArrayNode all = (ArrayNode) page.get("transactions");
            assertThat(page.get("total").asLong()).isEqualTo(all.size());
            List<JsonNode> withoutOpenings = new ArrayList<>();
            all.forEach(line -> {
                if (!line.get("source_event_id").asString().startsWith("opening_")) {
                    withoutOpenings.add(line);
                }
            });
            assertThat(withoutOpenings).as("lines of %s", id).containsExactlyElementsOf(linesByAccount.get(id));
        }
    }
}
