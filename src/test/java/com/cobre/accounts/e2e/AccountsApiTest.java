package com.cobre.accounts.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.adapters.out.persistence.PostgresLoadLock;
import com.cobre.accounts.application.port.out.LoadLock;
import com.cobre.accounts.support.ApiApp;
import com.cobre.accounts.support.ApiApp.Response;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * {@code /balance} and {@code /transactions} (spec §8.2, AC14) after loading F01 on the case seed.
 * Every expected body is computed by hand from §2, §6.3 and §7.1: the opening of ACC123456789
 * (200000.00, posted 1970-01-01T00:00:00Z) and the four lines of {@code cbmm_20250909_000123}.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AccountsApiTest {

    private static ApiApp app;

    @BeforeAll
    static void loadF01() {
        app = ApiApp.start("classpath:fixtures/F01-case-event/seed.json");
        assertThat(app.uploadLoad("F01-case-event", 1).status()).isEqualTo(200);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
    }

    // --- /balance ---------------------------------------------------------------------------

    @Test
    void AC14_balanceOfAClientAccount() {
        assertJson(app.get("/v1/accounts/ACC123456789/balance"), 200, """
                { "account_id": "ACC123456789", "type": "CLIENT", "status": "ACTIVE",
                  "currency": "MXN", "balance": "184999.50" }""");
    }

    @Test
    void AC14_balanceOfACobreAccountIsTheSumOfItsLines() {
        assertJson(app.get("/v1/accounts/COBRE_FUNDING_MXN/balance"), 200, """
                { "account_id": "COBRE_FUNDING_MXN", "type": "COBRE_FUNDING", "status": "ACTIVE",
                  "currency": "MXN", "balance": "-200000.00" }""");
        assertJson(app.get("/v1/accounts/COBRE_FX_POSITION_USD/balance"), 200, """
                { "account_id": "COBRE_FX_POSITION_USD", "type": "COBRE_POSITION", "status": "ACTIVE",
                  "currency": "USD", "balance": "-880.25" }""");
    }

    @Test
    void AC14_cobreAccountWithoutLinesHasBalanceZeroWithTwoDecimals() {
        // ACC987654321 starts at 0.00: no opening, so COBRE_FUNDING_USD has no lines (§6.3).
        assertJson(app.get("/v1/accounts/COBRE_FUNDING_USD/balance"), 200, """
                { "account_id": "COBRE_FUNDING_USD", "type": "COBRE_FUNDING", "status": "ACTIVE",
                  "currency": "USD", "balance": "0.00" }""");
    }

    /** Spec §8.2: queries read committed state and work during a load; they never take the load lock. */
    @Test
    void AC14_queriesAnswerWhileAnotherSessionHoldsTheLoadLock() {
        LoadLock otherSession = new PostgresLoadLock(app.db().appDataSource());
        try (LoadLock.Lease lease = otherSession.tryAcquire().orElseThrow()) {
            assertJson(app.get("/v1/accounts/ACC123456789/balance"), 200, """
                    { "account_id": "ACC123456789", "type": "CLIENT", "status": "ACTIVE",
                      "currency": "MXN", "balance": "184999.50" }""");
            assertThat(app.get("/v1/accounts/ACC123456789/transactions").status()).isEqualTo(200);
        }
    }

    // --- /transactions ----------------------------------------------------------------------

    @Test
    void AC14_transactionsIncludeTheOpeningFirstWithTheTotalAndDefaultPaging() {
        assertJson(app.get("/v1/accounts/ACC123456789/transactions"), 200, """
                { "account_id": "ACC123456789", "currency": "MXN", "total": 2, "limit": 100, "offset": 0,
                  "transactions": [
                    { "source_event_id": "opening_ACC123456789", "direction": "CREDIT",
                      "amount": "200000.00", "posted_at": "1970-01-01T00:00:00Z" },
                    { "source_event_id": "cbmm_20250909_000123", "direction": "DEBIT",
                      "amount": "15000.50", "posted_at": "2025-09-09T15:32:10Z" } ] }""");
    }

    @Test
    void AC14_transactionsOfACobreAccount() {
        assertJson(app.get("/v1/accounts/COBRE_FUNDING_MXN/transactions?limit=10&offset=0"), 200, """
                { "account_id": "COBRE_FUNDING_MXN", "currency": "MXN", "total": 1, "limit": 10, "offset": 0,
                  "transactions": [
                    { "source_event_id": "opening_ACC123456789", "direction": "DEBIT",
                      "amount": "200000.00", "posted_at": "1970-01-01T00:00:00Z" } ] }""");
    }

    @Test
    void AC14_limitAndOffsetSelectAPageAndTotalCountsEveryLine() {
        assertJson(app.get("/v1/accounts/ACC123456789/transactions?limit=1&offset=1"), 200, """
                { "account_id": "ACC123456789", "currency": "MXN", "total": 2, "limit": 1, "offset": 1,
                  "transactions": [
                    { "source_event_id": "cbmm_20250909_000123", "direction": "DEBIT",
                      "amount": "15000.50", "posted_at": "2025-09-09T15:32:10Z" } ] }""");
        assertJson(app.get("/v1/accounts/ACC123456789/transactions?limit=1000&offset=5"), 200, """
                { "account_id": "ACC123456789", "currency": "MXN", "total": 2, "limit": 1000, "offset": 5,
                  "transactions": [] }""");
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=1001", "limit=-1", "limit=abc", "limit=1.5", "offset=-1",
        "offset=abc", "limit=", "limit=99999999999"})
    void invalidPaginationIs400(String query) {
        Response response = app.get("/v1/accounts/ACC123456789/transactions?" + query);

        assertProblem(response, 400, "INVALID_PAGINATION");
    }

    @Test
    void invalidPaginationOnAnUnknownAccountIsStill400() {
        // The request is checked before the account is looked up: nothing is read for a bad request.
        assertProblem(app.get("/v1/accounts/ACC000000000/transactions?limit=0"), 400, "INVALID_PAGINATION");
    }

    // --- 404 --------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ACC000000000", "COBRE_FX_POSITION_EUR", "acc123456789", "%C3%B1and%C3%BA",
        "ACC98765432100000000000000000000000000000000000000000000000000000"})
    void AC14_unknownAccountIs404OnBothQueries(String id) {
        assertProblem(app.get("/v1/accounts/" + id + "/balance"), 404, "ACCOUNT_NOT_FOUND");
        assertProblem(app.get("/v1/accounts/" + id + "/transactions"), 404, "ACCOUNT_NOT_FOUND");
    }

    // --- Swagger (spec §8.2) ----------------------------------------------------------------

    @Test
    void openApiDocumentsTheThreeEndpoints() {
        Response docs = app.get("/v3/api-docs");

        assertThat(docs.status()).isEqualTo(200);
        JsonNode paths = docs.json().get("paths");
        assertThat(List.of("/v1/event-files", "/v1/accounts/{account_id}/balance",
                "/v1/accounts/{account_id}/transactions")).allSatisfy(path -> assertThat(paths.has(path))
                        .as("documents %s", path).isTrue());
    }

    @Test
    void swaggerUiIsServed() {
        Response ui = app.get("/swagger-ui.html");

        assertThat(ui.status()).isEqualTo(200);
        assertThat(ui.body()).containsIgnoringCase("swagger");
    }

    // --- Helpers ----------------------------------------------------------------------------

    private static void assertJson(Response response, int status, String expected) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/json");
        assertThat(response.json()).isEqualTo(ApiApp.JSON.readTree(expected));
    }

    static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/problem+json");
        JsonNode body = response.json();
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asString()).isEqualTo(code);
    }
}
