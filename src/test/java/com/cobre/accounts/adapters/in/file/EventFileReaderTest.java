package com.cobre.accounts.adapters.in.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.FileStatus;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.RejectionCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** File formats, detection and unreadable files (spec §6.2, AC13). */
class EventFileReaderTest {

    private final EventFileReader reader = new EventFileReader(
            new CbmmEventTranslator(Set.of(CurrencyCode.of("MXN"), CurrencyCode.of("USD"))));

    static String event(String eventId) {
        return """
                {"event_id":"%s","event_type":"cross_border_money_movement","operation_date":"2025-09-09T15:32:10Z",\
                "origin":{"account_id":"ACC123456789","currency":"MXN","amount":15000.50},\
                "destination":{"account_id":"ACC987654321","currency":"USD","amount":880.25}}"""
                .formatted(eventId);
    }

    private EventFile read(String name, String content) {
        return reader.read(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] fixture(String path) {
        try (InputStream in = EventFileReaderTest.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> eventIds(EventFile file) {
        return file.events().stream().map(ReadEvent::eventId).toList();
    }

    // --- AC13 -------------------------------------------------------------------------------

    @Test
    void AC13_truncatedArrayIsUnreadable() {
        String truncated = "[" + event("e1") + "," + event("e2").substring(0, 40);

        EventFile file = read("truncated.json", truncated);

        assertThat(file.status()).isEqualTo(FileStatus.UNREADABLE);
        assertThat(file.events()).isEmpty();
    }

    @Test
    void AC13_fixtureF06TruncatedFileIsUnreadableAndValidFileIsRead() {
        EventFile truncated = reader.read("F06-truncated.json", fixture("F06-truncated-file/loads/1/F06-truncated.json"));
        EventFile valid = reader.read("F06-valid.json", fixture("F06-truncated-file/loads/1/F06-valid.json"));

        assertThat(truncated.status()).isEqualTo(FileStatus.UNREADABLE);
        assertThat(truncated.events()).isEmpty();
        assertThat(valid.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(valid.events()).hasSize(2).allMatch(e -> e.outcome() instanceof ReadEvent.Translated);
    }

    @Test
    void AC13_readingIsPerFile_anUnreadableFileDoesNotAffectTheNextOne() {
        EventFile broken = read("a.json", "[" + event("e1"));
        EventFile fine = read("b.json", "[" + event("e2") + "]");

        assertThat(broken.status()).isEqualTo(FileStatus.UNREADABLE);
        assertThat(fine.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(eventIds(fine)).containsExactly("e2");
    }

    @Test
    void arrayWithTrailingGarbageIsUnreadable() {
        assertThat(read("a.json", "[" + event("e1") + "] x").status()).isEqualTo(FileStatus.UNREADABLE);
    }

    @Test
    void emptyOrUnknownContentIsUnreadable() {
        assertThat(read("a.json", "").status()).isEqualTo(FileStatus.UNREADABLE);
        assertThat(read("a.json", "  \n ").status()).isEqualTo(FileStatus.UNREADABLE);
        assertThat(read("a.json", "event_id,amount").status()).isEqualTo(FileStatus.UNREADABLE);
    }

    @Test
    void invalidUtf8IsUnreadable() {
        byte[] content = {'[', (byte) 0xC3, (byte) 0x28, ']'};

        assertThat(reader.read("a.json", content).status()).isEqualTo(FileStatus.UNREADABLE);
    }

    /**
     * Jackson's default limit is 1000 characters per JSON number (StreamReadConstraints). It is not
     * raised: a longer number makes the array invalid as a whole, so the file is UNREADABLE (§6.2).
     */
    @Test
    void numberLongerThanJacksonLimitInsideArrayMakesTheFileUnreadable() {
        String huge = "1" + "0".repeat(1000);
        String content = "[" + event("e1") + "," + event("e2").replace("15000.50", huge) + "]";

        EventFile file = read("a.json", content);

        assertThat(file.status()).isEqualTo(FileStatus.UNREADABLE);
        assertThat(file.events()).isEmpty();
    }

    @Test
    void numberLongerThanJacksonLimitOnAnNdjsonLineIsOnlyThatLine() {
        String huge = "1" + "0".repeat(1000);
        String content = event("e1") + "\n" + event("e2").replace("15000.50", huge) + "\n" + event("e3");

        EventFile file = read("a.ndjson", content);

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(file.events().get(1).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MALFORMED_JSON"));
        assertThat(file.events().get(2).outcome()).isInstanceOf(ReadEvent.Translated.class);
    }

    /**
     * An exponent beyond BigDecimal's int scale makes Jackson throw NumberFormatException, which is
     * not a JacksonException. It must not escape the reader (D8): same treatment as a number longer
     * than the parser limit.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1e99999999999999999999999", "1E-99999999999", "-1E+99999999999"})
    void numberBeyondBigDecimalRange_insideArray_makesTheFileUnreadable(String number) {
        String content = "[" + event("e1") + "," + event("e2").replace("15000.50", number) + "]";

        EventFile file = read("a.json", content);

        assertThat(file.status()).isEqualTo(FileStatus.UNREADABLE);
        assertThat(file.events()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e99999999999999999999999", "1E-99999999999", "-1E+99999999999"})
    void numberBeyondBigDecimalRange_singleObject_isUnreadable(String number) {
        EventFile file = read("a.json", event("e1").replace("15000.50", number));

        assertThat(file.status()).isEqualTo(FileStatus.UNREADABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e99999999999999999999999", "1E-99999999999", "-1E+99999999999"})
    void numberBeyondBigDecimalRange_onAnNdjsonLine_isOnlyThatLine(String number) {
        String content = event("e1") + "\n" + event("e2").replace("15000.50", number) + "\n" + event("e3");

        EventFile file = read("a.ndjson", content);

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(file.events()).hasSize(3);
        assertThat(file.events().get(1).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MALFORMED_JSON"));
        assertThat(file.events().get(2).outcome()).isInstanceOf(ReadEvent.Translated.class);
    }

    /**
     * Numbers of more than ~500 characters go through another conversion path in jackson-core. With
     * an overflowing exponent it also throws NumberFormatException, which must not escape (D8).
     */
    @Test
    void longNumberWithOverflowingExponent_isUnreadableInArrayAndMalformedOnNdjsonLine() {
        String longNumber = "1" + "0".repeat(600) + "e99999999999";

        EventFile array = read("a.json", "[" + event("e1") + "," + event("e2").replace("15000.50", longNumber) + "]");
        EventFile ndjson = read("a.ndjson", event("e1") + "\n" + event("e2").replace("15000.50", longNumber));

        assertThat(array.status()).isEqualTo(FileStatus.UNREADABLE);
        assertThat(ndjson.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(ndjson.events().get(1).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MALFORMED_JSON"));
    }

    @Test
    void numberBeyondBigDecimalRange_afterTheFirstObject_isNdjson() {
        String content = event("e1") + "\n" + event("e2").replace("15000.50", "1e99999999999999999999999");

        EventFile file = read("a.ndjson", content);

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(eventIds(file).getFirst()).isEqualTo("e1");
        assertThat(file.events().get(1).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MALFORMED_JSON"));
    }

    // --- Formats (§6.2) ---------------------------------------------------------------------

    @Test
    void arrayOfEvents() {
        EventFile file = read("a.json", " \n[" + event("e1") + ",\n" + event("e2") + "]\n");

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(eventIds(file)).containsExactly("e1", "e2");
        assertThat(file.events()).extracting(ReadEvent::position).containsExactly(0, 1);
        assertThat(file.events()).extracting(ReadEvent::fileName).containsOnly("a.json");
    }

    @Test
    void emptyArrayIsProcessedWithoutEvents() {
        EventFile file = read("a.json", "[]");

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(file.events()).isEmpty();
    }

    @Test
    void singleObject_evenPrettyPrinted() {
        String pretty = event("e1").replace(",", ",\n  ");

        EventFile file = read("a.json", "﻿" + pretty + "\n\n");

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(eventIds(file)).containsExactly("e1");
        assertThat(file.events().getFirst().position()).isZero();
    }

    @Test
    void ndjson_positionsCountOnlyNonEmptyLines() {
        String content = event("e1") + "\r\n\n   \n" + event("e2") + "\n" + event("e3");

        EventFile file = read("a.ndjson", content);

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(eventIds(file)).containsExactly("e1", "e2", "e3");
        assertThat(file.events()).extracting(ReadEvent::position).containsExactly(0, 1, 2);
    }

    @Test
    void ndjson_brokenLineIsMalformedJsonAndTheOthersAreRead() {
        String content = event("e1") + "\n{\"event_id\":\"e2\",\n" + event("e3");

        EventFile file = read("a.ndjson", content);

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(file.events()).hasSize(3);
        assertThat(file.events().get(1).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MALFORMED_JSON"));
        assertThat(file.events().get(1).eventId()).isNull();
        assertThat(file.events().get(0).outcome()).isInstanceOf(ReadEvent.Translated.class);
        assertThat(file.events().get(2).outcome()).isInstanceOf(ReadEvent.Translated.class);
    }

    @Test
    void ndjson_twoObjectsOnOneLineIsMalformed() {
        String content = event("e1") + " " + event("e2") + "\n" + event("e3");

        EventFile file = read("a.ndjson", content);

        assertThat(file.events()).hasSize(2);
        assertThat(file.events().get(0).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MALFORMED_JSON"));
        assertThat(eventIds(file).get(1)).isEqualTo("e3");
    }

    @Test
    void ndjson_lineThatIsNotAnObjectIsMalformed() {
        String content = event("e1") + "\n42\n" + event("e3");

        EventFile file = read("a.ndjson", content);

        assertThat(file.events().get(1).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MALFORMED_JSON"));
    }

    // --- Detection heuristic for files starting with '{' (§6.2) ------------------------------

    @Test
    void detection_ndjsonWithBrokenLineZero_isMalformedOnLineZeroAndTheOthersAreRead() {
        String content = "{\"event_id\":\"e1\",\"event_type\"\n" + event("e2") + "\n" + event("e3");

        EventFile file = read("a.ndjson", content);

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(file.events()).hasSize(3);
        assertThat(file.events().get(0).position()).isZero();
        assertThat(file.events().get(0).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, "MALFORMED_JSON"));
        assertThat(eventIds(file).subList(1, 3)).containsExactly("e2", "e3");
    }

    @Test
    void detection_truncatedMultiLineObjectIsUnreadable() {
        String pretty = """
                {
                  "event_id": "e1",
                  "event_type": "cross_border_money_movement",
                  "operation_date": "2025-09-09T15:32:10Z",
                  "origin": { "account_id": "ACC123456789", "currency": "MXN", "amount": 15000.50 },
                  "destination": { "account_id": "ACC987654321",
                """;

        EventFile file = read("a.json", pretty);

        assertThat(file.status()).isEqualTo(FileStatus.UNREADABLE);
        assertThat(file.events()).isEmpty();
    }

    @Test
    void detection_isLinearOnALongBrokenFile() {
        String content = "{\"event_id\":\"e1\",\n" + "\"x\": 1,\n".repeat(200_000);

        EventFile file = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> read("a.json", content));

        assertThat(file.status()).isEqualTo(FileStatus.UNREADABLE);
    }
}
