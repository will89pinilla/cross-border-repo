package com.cobre.accounts.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.support.ApiApp;
import com.cobre.accounts.support.ApiApp.Response;
import com.cobre.accounts.support.FreshDatabase;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * Spec §11 (D15): the service logs JSON in Spring Boot's ECS format, with {@code load_id} and
 * {@code event_id}. Logs carry codes, file names and positions, never the content of the events nor
 * the bytes of the files (T09 notes).
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

    private static final String F02_EVENT = "cbmm_20250909_000201";

    @Test
    void S11_everyLineOfTheServiceIsEcsJson(CapturedOutput output) {
        FreshDatabase db = FreshDatabase.create();
        int from = output.getOut().length();
        try (ApiApp app = ApiApp.start(db, "classpath:fixtures/F02-insufficient-funds/seed.json", List.of())) {
            assertThat(app.uploadLoad("F02-insufficient-funds", 1).status()).isEqualTo(200);

            List<String> lines = serviceLines(output, from);

            assertThat(lines).isNotEmpty();
            for (String line : lines) {
                JsonNode json = ApiApp.JSON.readTree(line);
                assertThat(json.has("@timestamp")).as(line).isTrue();
                assertThat(json.has("log.level") || json.path("log").has("level")).as(line).isTrue();
                assertThat(json.has("message")).as(line).isTrue();
            }
        }
    }

    @Test
    void S11_rejectionLogHasLoadIdAndEventId(CapturedOutput output) {
        FreshDatabase db = FreshDatabase.create();
        int from = output.getOut().length();
        try (ApiApp app = ApiApp.start(db, "classpath:fixtures/F02-insufficient-funds/seed.json", List.of())) {
            Response report = app.uploadLoad("F02-insufficient-funds", 1);
            assertThat(report.status()).isEqualTo(200);
            String loadId = report.json().path("metadata").path("load_id").asString();

            List<JsonNode> rejections = serviceLines(output, from).stream()
                    .map(ApiApp.JSON::readTree)
                    .filter(json -> json.path("message").asString().contains("PostingRejected"))
                    .toList();

            assertThat(rejections).hasSize(1);
            JsonNode rejection = rejections.getFirst();
            assertThat(rejection.path("load_id").asString()).isEqualTo(loadId);
            assertThat(rejection.path("event_id").asString()).isEqualTo(F02_EVENT);
            // Where it is in the input, as in the report's rejected[] (§8.1): F02.json, position 0.
            assertThat(rejection.path("file").asString()).isEqualTo("F02.json");
            assertThat(rejection.path("position").asString()).isEqualTo("0");
            assertThat(rejection.path("message").asString()).contains("INSUFFICIENT_FUNDS");
        }
    }

    /** F06: an UNREADABLE file (spec §6.2, AC13) leaves a WARN with the load and the file name. */
    @Test
    void S11_unreadableFileLogsAWarnWithLoadIdAndFile(CapturedOutput output) {
        FreshDatabase db = FreshDatabase.create();
        int from = output.getOut().length();
        try (ApiApp app = ApiApp.start(db, "classpath:fixtures/F06-truncated-file/seed.json", List.of())) {
            Response report = app.uploadLoad("F06-truncated-file", 1);
            assertThat(report.status()).isEqualTo(200);
            String loadId = report.json().path("metadata").path("load_id").asString();

            List<JsonNode> unreadable = serviceLines(output, from).stream()
                    .map(ApiApp.JSON::readTree)
                    .filter(json -> json.path("message").asString().contains("UNREADABLE"))
                    .toList();

            assertThat(unreadable).hasSize(1);
            JsonNode warn = unreadable.getFirst();
            assertThat(warn.path("log").path("level").asString()).isEqualTo("WARN");
            assertThat(warn.path("load_id").asString()).isEqualTo(loadId);
            assertThat(warn.path("file").asString()).isEqualTo("F06-truncated.json");
        }
    }

    @Test
    void S11_loadSummaryLogHasLoadIdAndCounts(CapturedOutput output) {
        FreshDatabase db = FreshDatabase.create();
        int from = output.getOut().length();
        try (ApiApp app = ApiApp.start(db, "classpath:fixtures/F03-duplicates/seed.json", List.of())) {
            Response report = app.uploadLoad("F03-duplicates", 1);
            assertThat(report.status()).isEqualTo(200);
            String loadId = report.json().path("metadata").path("load_id").asString();

            List<JsonNode> summaries = serviceLines(output, from).stream()
                    .map(ApiApp.JSON::readTree)
                    .filter(json -> json.path("message").asString().startsWith("Load finished"))
                    .toList();

            assertThat(summaries).hasSize(1);
            JsonNode summary = summaries.getFirst();
            assertThat(summary.path("load_id").asString()).isEqualTo(loadId);
            assertThat(summary.has("event_id")).isFalse();
            // F03 expected-1.json: 2 files, 4 events read, 2 applied, 0 rejected, 2 duplicates.
            assertThat(summary.path("message").asString())
                    .contains("2 file(s)", "4 event(s) read", "2 applied", "0 rejected", "2 duplicate(s)");
        }
    }

    @Test
    void S11_logsNeverCarryTheEventContentNorTheFileBytes(CapturedOutput output) {
        FreshDatabase db = FreshDatabase.create();
        int from = output.getOut().length();
        try (ApiApp app = ApiApp.start(db, "classpath:fixtures/F06-truncated-file/seed.json", List.of())) {
            assertThat(app.uploadLoad("F06-truncated-file", 1).status()).isEqualTo(200);
            List<String> lines = serviceLines(output, from);

            // Keys of the CBMM event (spec §6.1): only a raw event or file would put them in a log.
            assertThat(lines).noneMatch(line -> line.contains("operation_date")
                    || line.contains("event_type") || line.contains("cross_border_money_movement")
                    || line.contains("origin\\\"") || line.contains("destination\\\""));
            // The truncated file is never parsed into events: nothing of its content may be logged,
            // neither its event ids, nor its amounts, nor the cut-off fragment it ends with.
            assertThat(lines).noneMatch(line -> line.contains("cbmm_20250909_000611")
                    || line.contains("cbmm_20250909_000612") || line.contains("2000.00")
                    || line.contains("110.20") || line.contains("\"MXN\", \"amou")
                    || line.contains("MXN\\\", \\\"amou"));
            // The valid file's events are posted: the domain events of §8.3 are the only trace of them.
            assertThat(lines).anyMatch(line -> line.contains("cbmm_20250909_000601"));
        }
    }

    /** Lines written once the service started on {@code db}: Flyway's migration of the test database is not the service. */
    private static List<String> serviceLines(CapturedOutput output, int from) {
        return output.getOut().substring(from).lines().filter(line -> !line.isBlank()).toList();
    }
}
