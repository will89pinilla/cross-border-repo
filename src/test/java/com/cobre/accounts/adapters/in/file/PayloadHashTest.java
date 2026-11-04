package com.cobre.accounts.adapters.in.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.domain.model.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code payload_hash} of spec §9: SHA-256 in hex over the canonical JSON of the event (keys sorted
 * recursively, no blanks, each number as written in the text). Events go through the reader so the
 * three file forms of §6.2 are covered.
 */
class PayloadHashTest {

    private final EventFileReader reader = new EventFileReader(
            new CbmmEventTranslator(Set.of(CurrencyCode.of("MXN"), CurrencyCode.of("USD"))));

    private static final String EVENT = """
            {
              "event_type": "cross_border_money_movement",
              "event_id": "cbmm_20250909_000123",
              "operation_date": "2025-09-09T15:32:10Z",
              "origin": { "currency": "MXN", "account_id": "ACC123456789", "amount": 15000.50 },
              "destination": { "account_id": "ACC987654321", "currency": "USD", "amount": 880.25 }
            }""";

    /** {@link #EVENT} written by hand following §9: sorted keys, no blanks, numbers as written. */
    private static final String EVENT_CANONICAL = "{\"destination\":{\"account_id\":\"ACC987654321\",\"amount\":880.25,"
            + "\"currency\":\"USD\"},\"event_id\":\"cbmm_20250909_000123\",\"event_type\":\"cross_border_money_movement\","
            + "\"operation_date\":\"2025-09-09T15:32:10Z\",\"origin\":{\"account_id\":\"ACC123456789\","
            + "\"amount\":15000.50,\"currency\":\"MXN\"}}";

    private List<ReadEvent> read(String content) {
        EventFile file = reader.read("f.json", content.getBytes(StandardCharsets.UTF_8));
        return file.events();
    }

    private String hashOf(String singleObject) {
        return read(singleObject).getFirst().payloadHash();
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void hashIsSha256HexOfTheCanonicalJson() {
        String hash = hashOf(EVENT);

        assertThat(hash).isEqualTo(sha256Hex(EVENT_CANONICAL));
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void fileFormDoesNotChangeTheHash() {
        String oneLine = EVENT.replace("\n", "");
        String other = EVENT.replace("cbmm_20250909_000123", "cbmm_other");

        List<ReadEvent> array = read("[ " + oneLine + " ,\n " + other.replace("\n", "") + " ]");
        List<ReadEvent> ndjson = read(oneLine + "\n" + other.replace("\n", "") + "\n");

        assertThat(array).extracting(ReadEvent::payloadHash)
                .containsExactly(sha256Hex(EVENT_CANONICAL), sha256Hex(EVENT_CANONICAL.replace(
                        "cbmm_20250909_000123", "cbmm_other")));
        assertThat(ndjson).extracting(ReadEvent::payloadHash)
                .isEqualTo(array.stream().map(ReadEvent::payloadHash).toList());
        assertThat(hashOf(EVENT)).isEqualTo(array.getFirst().payloadHash());
    }

    @Test
    void keyOrderAndBlanksDoNotChangeTheHash() {
        String reordered = """
                {"destination":{"currency":"USD","amount":880.25,"account_id":"ACC987654321"},
                 "origin":{"amount":15000.50,"account_id":"ACC123456789","currency":"MXN"},
                 "operation_date":"2025-09-09T15:32:10Z","event_id":"cbmm_20250909_000123",
                 "event_type":"cross_border_money_movement"}""";

        assertThat(hashOf(reordered)).isEqualTo(hashOf(EVENT));
    }

    @Test
    void numbersAreHashedAsWrittenInTheText() {
        String asNumber = hashOf(EVENT);
        String asText = hashOf(EVENT.replace("15000.50", "\"15000.50\""));
        String withoutTrailingZero = hashOf(EVENT.replace("15000.50", "15000.5"));
        String exponent = hashOf(EVENT.replace("15000.50", "1.500050e4"));
        String exponentUpper = hashOf(EVENT.replace("15000.50", "1.500050E+4"));

        assertThat(List.of(asNumber, asText, withoutTrailingZero, exponent, exponentUpper)).doesNotHaveDuplicates();
        assertThat(exponent).isEqualTo(sha256Hex(EVENT_CANONICAL.replace("15000.50", "1.500050e4")));
    }

    @Test
    void additionalFieldsArePartOfTheContent() {
        String extra = EVENT.replace("\"event_type\"", "\"trace\": {\"b\": 1, \"a\": [2, 1]}, \"event_type\"");

        assertThat(hashOf(extra)).isNotEqualTo(hashOf(EVENT));
        // "trace" sorts after "origin"; its keys are sorted too, array order is kept.
        assertThat(hashOf(extra)).isEqualTo(sha256Hex(
                EVENT_CANONICAL.substring(0, EVENT_CANONICAL.length() - 1) + ",\"trace\":{\"a\":[2,1],\"b\":1}}"));
    }

    @Test
    void equalStringsWithDifferentEscapesHaveTheSameHash() {
        assertThat(hashOf(EVENT.replace("\"MXN\"", "\"\\u004dXN\""))).isEqualTo(hashOf(EVENT));
    }

    @Test
    void invalidEventWithIdentityHasAHash_malformedOneHasNone() {
        List<ReadEvent> events = read(EVENT.replace("\n", "").replace("\"MXN\"", "\"EUR\"") + "\n{ not json\n");

        assertThat(events.getFirst().hasIdentity()).isTrue();
        assertThat(events.getFirst().outcome()).isInstanceOf(ReadEvent.Invalid.class);
        assertThat(events.getFirst().payloadHash()).hasSize(64);
        assertThat(events.get(1).payloadHash()).isNull();
    }

    @Test
    void literalsAndNestedArraysAreCanonical() {
        String withLiterals = EVENT.replace("\"event_type\"", "\"x\": [ true, false, null, { \"z\": 1, \"y\": -0 } ], \"event_type\"");

        assertThat(hashOf(withLiterals)).isEqualTo(sha256Hex(
                EVENT_CANONICAL.substring(0, EVENT_CANONICAL.length() - 1) + ",\"x\":[true,false,null,{\"y\":-0,\"z\":1}]}"));
    }
}
