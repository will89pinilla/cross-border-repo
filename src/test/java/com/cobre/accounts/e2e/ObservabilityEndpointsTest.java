package com.cobre.accounts.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.support.ApiApp;
import com.cobre.accounts.support.ApiApp.Response;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Spec §8.2 and §11 (D15) through real HTTP: {@code /actuator/health} and
 * {@code /actuator/prometheus} are the only Actuator endpoints (T07 security review: {@code env},
 * {@code configprops} or {@code heapdump} would show the database passwords). Expected counts come
 * from the fixtures' {@code expected*.json} summaries, which are written from the spec.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ObservabilityEndpointsTest {

    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)})?\\s+(\\S+)$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void S11_healthIsUpWithoutDetails() {
        try (ApiApp app = ApiApp.start("classpath:fixtures/F01-case-event/seed.json")) {
            Response health = app.get("/actuator/health");

            assertThat(health.status()).isEqualTo(200);
            assertThat(health.json().path("status").asString()).isEqualTo("UP");
            // Boot lists the names of its liveness/readiness groups; never components nor details.
            assertThat(health.json().has("components")).as(health.body()).isFalse();
            assertThat(health.json().has("details")).as(health.body()).isFalse();
            assertThat(health.body()).doesNotContain("db", "PostgreSQL", "diskSpace");
        }
    }

    @Test
    void S11_onlyHealthAndPrometheusAreExposed() {
        try (ApiApp app = ApiApp.start("classpath:fixtures/F01-case-event/seed.json")) {
            for (String hidden : List.of("env", "configprops", "heapdump", "beans", "loggers", "threaddump",
                    "metrics", "mappings", "info", "shutdown")) {
                assertThat(app.get("/actuator/" + hidden).status()).as("/actuator/" + hidden).isEqualTo(404);
            }
            assertThat(app.get("/actuator/prometheus").status()).isEqualTo(200);
        }
    }

    /** F02: one INSUFFICIENT_FUNDS. F03: 2 applied and 2 duplicates, then 4 duplicates. F06: one unreadable file. */
    @Test
    void S11_prometheusExposesTheCbmmMetrics() {
        try (ApiApp app = ApiApp.start("classpath:fixtures/F03-duplicates/seed.json")) {
            assertThat(app.uploadLoad("F03-duplicates", 1).status()).isEqualTo(200);
            assertThat(app.uploadLoad("F03-duplicates", 2).status()).isEqualTo(200);

            Map<String, Double> samples = scrape(app);

            assertThat(samples).containsEntry("cbmm_events_total{code=none,result=applied}", 2.0)
                    .containsEntry("cbmm_events_total{code=none,result=duplicate}", 6.0)
                    .containsEntry("cbmm_files_total{status=processed}", 4.0)
                    .containsEntry("cbmm_load_duration_seconds_count", 2.0)
                    // Only the first of each event_id in a load is posted (§7.5): 2 ids in each load.
                    .containsEntry("cbmm_event_posting_seconds_count", 4.0)
                    .containsKey("cbmm_load_duration_seconds_sum")
                    .containsKey("cbmm_event_posting_seconds_sum");
        }
        try (ApiApp app = ApiApp.start("classpath:fixtures/F02-insufficient-funds/seed.json")) {
            assertThat(app.uploadLoad("F02-insufficient-funds", 1).status()).isEqualTo(200);

            assertThat(scrape(app)).containsEntry("cbmm_events_total{code=INSUFFICIENT_FUNDS,result=rejected}", 1.0)
                    .containsEntry("cbmm_files_total{status=processed}", 1.0)
                    .doesNotContainKey("cbmm_events_total{code=none,result=applied}");
        }
        try (ApiApp app = ApiApp.start("classpath:fixtures/F06-truncated-file/seed.json")) {
            assertThat(app.uploadLoad("F06-truncated-file", 1).status()).isEqualTo(200);

            assertThat(scrape(app)).containsEntry("cbmm_files_total{status=unreadable}", 1.0)
                    .containsEntry("cbmm_files_total{status=processed}", 1.0)
                    .containsEntry("cbmm_events_total{code=none,result=applied}", 2.0);
        }
    }

    @Test
    void S11_noMetricHasAnAccountIdLabelNorAnAccountId() {
        try (ApiApp app = ApiApp.start("classpath:fixtures/F04-validation-rejections/seed.json")) {
            assertThat(app.uploadLoad("F04-validation-rejections", 1).status()).isEqualTo(200);

            String body = app.get("/actuator/prometheus").body();
            List<String> cbmm = body.lines().filter(line -> line.startsWith("cbmm_")).toList();

            List<String> seedIds = ApiApp.readJson(ApiApp.fixture("F04-validation-rejections/seed.json"))
                    .path("accounts").valueStream().map(account -> account.path("account_id").asString()).toList();

            assertThat(seedIds).isNotEmpty();
            assertThat(cbmm).isNotEmpty();
            assertThat(cbmm).noneMatch(line -> line.contains("account_id"))
                    .noneMatch(line -> seedIds.stream().anyMatch(line::contains))
                    .noneMatch(line -> line.contains("COBRE_"));
        }
    }

    /** Samples as {@code name{label=value,...}} with labels sorted by name, or just {@code name}. */
    private static Map<String, Double> scrape(ApiApp app) {
        Response response = app.get("/actuator/prometheus");
        assertThat(response.status()).isEqualTo(200);
        Map<String, Double> samples = new TreeMap<>();
        response.body().lines().filter(line -> !line.isBlank() && !line.startsWith("#")).forEach(line -> {
            Matcher sample = SAMPLE.matcher(line);
            assertThat(sample.matches()).as(line).isTrue();
            String key = sample.group(1);
            if (sample.group(2) != null && !sample.group(2).isEmpty()) {
                Map<String, String> labels = new TreeMap<>();
                Matcher label = LABEL.matcher(sample.group(2));
                while (label.find()) {
                    labels.put(label.group(1), label.group(2));
                }
                key += labels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(",", "{", "}"));
            }
            samples.put(key, Double.parseDouble(sample.group(3)));
        });
        return samples;
    }
}
