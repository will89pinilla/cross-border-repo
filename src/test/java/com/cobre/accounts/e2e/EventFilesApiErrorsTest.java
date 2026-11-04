package com.cobre.accounts.e2e;

import static com.cobre.accounts.e2e.AccountsApiTest.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.adapters.out.persistence.PostgresLoadLock;
import com.cobre.accounts.application.port.out.LoadLock;
import com.cobre.accounts.support.ApiApp;
import com.cobre.accounts.support.ApiApp.Part;
import com.cobre.accounts.support.ApiApp.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Errors of {@code POST /v1/event-files} with the default limits (spec §8.2): 400 {@code NO_FILES},
 * 409 {@code LOAD_IN_PROGRESS} (AC16 through the API) and 500 {@code INTERNAL_ERROR} without the
 * exception's trace or message. Every error is {@code application/problem+json} with a {@code code}.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EventFilesApiErrorsTest {

    private static ApiApp app;

    @BeforeAll
    static void start() {
        app = ApiApp.start("classpath:fixtures/F01-case-event/seed.json");
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void noFilesIs400() {
        assertProblem(app.upload(List.of()), 400, "NO_FILES");
    }

    @Test
    void aMultipartWithoutTheFilesFieldIs400() {
        assertProblem(app.upload(List.of(new Part("other", null, "x".getBytes(StandardCharsets.UTF_8)))), 400,
                "NO_FILES");
        assertProblem(app.upload(List.of(new Part("other", "F01.json", "[]".getBytes(StandardCharsets.UTF_8)))),
                400, "NO_FILES");
    }

    /**
     * Spec §6.2: 1,000 files per load with the default configuration. Tomcat's own default
     * ({@code server.tomcat.max-part-count}) is 50 parts, so this fails unless application.yml sets it.
     */
    @Test
    void thousandFilesAreOneLoadAndTheThousandAndFirstIs413() {
        List<Part> thousand = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            thousand.add(Part.file("empty-%04d.json".formatted(i), "[]".getBytes(StandardCharsets.UTF_8)));
        }

        Response accepted = app.upload(thousand);
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(200);
        assertThat(accepted.json().get("files")).hasSize(1000);

        thousand.add(Part.file("empty-1000.json", "[]".getBytes(StandardCharsets.UTF_8)));
        Response refused = app.upload(thousand);
        assertProblem(refused, 413, "PAYLOAD_TOO_LARGE");
        // The detail names the limit that was hit: the number of files, not their size.
        assertThat(refused.json().get("detail").asString()).isEqualTo("The load has more files than allowed");
    }

    /** AC16 through the API: the lock is held as another instance would hold it (spec §7.3). */
    @Test
    void AC16_aLoadWhileAnotherIsRunningIs409AndReadsNothing() {
        int decisionsBefore = app.count("settlement_decision");
        LoadLock otherInstance = new PostgresLoadLock(app.db().appDataSource());
        try (LoadLock.Lease lease = otherInstance.tryAcquire().orElseThrow()) {
            assertProblem(app.uploadLoad("F01-case-event", 1), 409, "LOAD_IN_PROGRESS");
            assertThat(app.count("settlement_decision")).isEqualTo(decisionsBefore);
        }
        assertThat(app.uploadLoad("F01-case-event", 1).status()).isEqualTo(200);
    }

    /**
     * A failure that is not a deadlock nor a serialization failure stops the load with 500 (spec
     * §7.3). The body says {@code INTERNAL_ERROR} and nothing about the cause; the lock is released,
     * so the next load is not refused with 409, and once the cause is gone the files are applied.
     */
    @Test
    void anUnexpectedFailureIs500WithoutTraceNorMessageAndReleasesTheLock() {
        JdbcClient owner = JdbcClient.create(app.db().ownerDataSource());
        String event = """
                [{"event_id": "cbmm_20250909_009001", "event_type": "cross_border_money_movement",
                  "operation_date": "2025-09-09T16:00:00Z",
                  "origin": {"account_id": "ACC123456789", "currency": "MXN", "amount": 10.00},
                  "destination": {"account_id": "ACC987654321", "currency": "USD", "amount": 0.55}}]""";
        List<Part> files = List.of(Part.file("E9001.json", event.getBytes(StandardCharsets.UTF_8)));
        owner.sql("REVOKE INSERT ON settlement_decision FROM accounts_app").update();
        try {
            for (int attempt = 1; attempt <= 2; attempt++) {
                Response response = app.upload(files);

                assertProblem(response, 500, "INTERNAL_ERROR");
                assertThat(response.json().has("trace")).isFalse();
                assertThat(response.body()).doesNotContainIgnoringCase("permission")
                        .doesNotContain("settlement_decision")
                        .doesNotContain("Exception")
                        .doesNotContain("at com.");
            }
        } finally {
            owner.sql("GRANT INSERT ON settlement_decision TO accounts_app").update();
        }
        Response retried = app.upload(files);
        assertThat(retried.status()).as(retried.body()).isEqualTo(200);
        assertThat(retried.json().get("summary").get("applied").asInt()).isEqualTo(1);
    }
}
