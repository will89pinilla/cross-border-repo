package com.cobre.accounts.e2e;

import static com.cobre.accounts.e2e.AccountsApiTest.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * The upload limits of spec §6.2, each answered with 413 {@code PAYLOAD_TOO_LARGE}, with small
 * limits so every body stays far below Tomcat's {@code max-swallow-size} (2 MB): above it Tomcat may
 * close the connection instead of answering (noted for the security review). A refused load decides
 * nothing and leaves the lock free.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class UploadLimitsApiTest {

    private static ApiApp app;

    @BeforeAll
    static void start() {
        app = ApiApp.start("classpath:fixtures/F01-case-event/seed.json",
                "--spring.servlet.multipart.max-file-size=1KB",
                "--spring.servlet.multipart.max-request-size=3KB",
                "--server.tomcat.max-part-count=3",
                "--cbmm.upload.max-events-per-load=2");
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void aFileAboveMaxFileSizeIs413() {
        assertRefused(List.of(Part.file("big.json", padded(2048))));
    }

    @Test
    void aLoadAboveMaxRequestSizeIs413() {
        // Three files of 1000 bytes: each below 1 KB, together (with the part headers) above 3 KB.
        assertRefused(List.of(Part.file("a.json", padded(1000)), Part.file("b.json", padded(1000)),
                Part.file("c.json", padded(1000))));
    }

    @Test
    void moreFilesThanMaxPartCountIs413() {
        assertRefused(List.of(Part.file("a.json", padded(2)), Part.file("b.json", padded(2)),
                Part.file("c.json", padded(2)), Part.file("d.json", padded(2))));
    }

    @Test
    void moreEventsThanMaxEventsPerLoadIs413AndDecidesNothing() {
        // F03-a has 2 events and F01 has 1: 3 > 2, although every file and the request are small.
        assertRefused(List.of(Part.file(ApiApp.fixture("F03-duplicates/loads/1/F03-a.json")),
                Part.file(ApiApp.fixture("F01-case-event/loads/1/F01.json"))));
    }

    @Test
    void aLoadWithinEveryLimitIsProcessed() {
        Response response = app.upload(List.of(Part.file(ApiApp.fixture("F03-duplicates/loads/1/F03-a.json"))));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    private static void assertRefused(List<Part> parts) {
        int decisionsBefore = app.count("settlement_decision");

        assertProblem(app.upload(parts), 413, "PAYLOAD_TOO_LARGE");

        assertThat(app.count("settlement_decision")).isEqualTo(decisionsBefore);
        // The lock is free: a load within the limits still runs (200; duplicates count as processed).
        assertThat(app.upload(List.of(Part.file(ApiApp.fixture("F01-case-event/loads/1/F01.json")))).status())
                .isEqualTo(200);
    }

    /** A valid, empty JSON array of exactly {@code size} bytes ({@code size >= 2}). */
    private static byte[] padded(int size) {
        return ("[" + " ".repeat(size - 2) + "]").getBytes(StandardCharsets.UTF_8);
    }
}
