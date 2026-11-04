package com.cobre.accounts.support;

import static com.cobre.accounts.support.SequentialOracle.duplicatesView;
import static com.cobre.accounts.support.SequentialOracle.rejectedView;
import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.adapters.in.file.CbmmEventTranslator;
import com.cobre.accounts.adapters.in.file.EventFileReader;
import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.support.SequentialOracle.Kind;
import com.cobre.accounts.support.SequentialOracle.Outcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * The sequential oracle of F07 (spec §12.1) is checked against fixtures whose {@code expected.json}
 * was written by hand from the spec: F03 (duplicates in one load and in a later one, payload
 * mismatch), F05 (D4 order across files) and F09 (D6: a rejection stays rejected). If the oracle
 * agrees with them on every rejection, every duplicate and every final balance, it can be trusted as
 * the reference for the generated loads. No database, no Spring.
 */
class SequentialOracleTest {

    private static final Set<CurrencyCode> CURRENCIES = Set.of(CurrencyCode.of("MXN"), CurrencyCode.of("USD"));
    private static final EventFileReader READER = new EventFileReader(new CbmmEventTranslator(CURRENCIES));

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "F03-duplicates, 'expected-1.json,expected-2.json'",
        "F05-operation-date-order, expected.json",
        "F09-rejected-stays-rejected, 'expected-1.json,expected-2.json,expected-3.json'"})
    void oracleAgreesWithTheHandWrittenExpectedReports(String fixture, String expectedFiles) throws IOException {
        SequentialOracle oracle = SequentialOracle.fromSeed(
                Files.readAllBytes(ApiApp.fixture(fixture + "/seed.json")), CURRENCIES);
        String[] expected = expectedFiles.split(",");

        for (int load = 1; load <= expected.length; load++) {
            List<Outcome> outcomes = oracle.load(read(ApiApp.loadFiles(fixture, load)));
            JsonNode report = ApiApp.readJson(ApiApp.fixture(fixture + "/" + expected[load - 1]));

            String context = fixture + " load " + load;
            assertThat(rejectedView(outcomes)).as(context + " rejected").isEqualTo(rejectedView(report));
            assertThat(duplicatesView(outcomes)).as(context + " duplicates").isEqualTo(duplicatesView(report));
            JsonNode summary = report.get("summary");
            assertThat(outcomes).as(context + " events read").hasSize(summary.get("events_read").asInt());
            assertThat(outcomes.stream().filter(o -> o.kind() == Kind.APPLIED).count())
                    .as(context + " applied").isEqualTo(summary.get("applied").asLong());
            for (JsonNode account : report.get("accounts")) {
                String id = account.get("account_id").asString();
                assertThat(oracle.balances().get(AccountId.of(id)).toPlainString())
                        .as(context + " final balance of " + id).isEqualTo(account.get("final_balance").asString());
            }
            for (var currency : report.get("invariant").properties()) {
                assertThat(oracle.invariant().get(CurrencyCode.of(currency.getKey())).toPlainString())
                        .as(context + " invariant " + currency.getKey()).isEqualTo(currency.getValue().asString());
            }
        }
    }

    private static List<EventFile> read(List<Path> paths) {
        return paths.stream().map(path -> {
            try {
                return READER.read(path.getFileName().toString(), Files.readAllBytes(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();
    }
}
