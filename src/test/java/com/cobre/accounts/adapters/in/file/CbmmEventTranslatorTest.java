package com.cobre.accounts.adapters.in.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.FileStatus;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.PostSettlement;
import com.cobre.accounts.domain.model.RejectionCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rules 1 and 2 of spec §7.2 and the translation to {@code PostSettlement} (§7.1). Events go
 * through {@link EventFileReader} so the real Jackson configuration (BigDecimal, never double) is
 * exercised.
 */
class CbmmEventTranslatorTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Duration LINEAR = Duration.ofSeconds(5);

    private final EventFileReader reader = new EventFileReader(new CbmmEventTranslator(Set.of(MXN, USD)));

    /** Event with raw JSON values per field; a null value omits the field. */
    private static final class Event {
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, String> origin = new LinkedHashMap<>();
        private final Map<String, String> destination = new LinkedHashMap<>();

        Event() {
            fields.put("event_id", "\"cbmm_20250909_000123\"");
            fields.put("event_type", "\"cross_border_money_movement\"");
            fields.put("operation_date", "\"2025-09-09T15:32:10Z\"");
            origin.put("account_id", "\"ACC123456789\"");
            origin.put("currency", "\"MXN\"");
            origin.put("amount", "15000.50");
            destination.put("account_id", "\"ACC987654321\"");
            destination.put("currency", "\"USD\"");
            destination.put("amount", "880.25");
        }

        Event set(String field, String raw) {
            fields.put(field, raw);
            return this;
        }

        Event origin(String field, String raw) {
            origin.put(field, raw);
            return this;
        }

        Event destination(String field, String raw) {
            destination.put(field, raw);
            return this;
        }

        String json() {
            Map<String, String> all = new LinkedHashMap<>(fields);
            if (!all.containsKey("origin")) {
                all.put("origin", object(origin));
            }
            if (!all.containsKey("destination")) {
                all.put("destination", object(destination));
            }
            return object(all);
        }

        private static String object(Map<String, String> raw) {
            return raw.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                    .collect(Collectors.joining(",", "{", "}"));
        }
    }

    private static Event event() {
        return new Event();
    }

    private static String text(String value) {
        return "\"" + value + "\"";
    }

    private ReadEvent read(Event event) {
        return read(event.json());
    }

    private ReadEvent read(String json) {
        EventFile file = reader.read("a.json", json.getBytes(StandardCharsets.UTF_8));
        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        assertThat(file.events()).hasSize(1);
        return file.events().getFirst();
    }

    private static ReadEvent.Invalid invalid(String detail) {
        return new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, detail);
    }

    private static PostSettlement command(ReadEvent event) {
        assertThat(event.outcome()).isInstanceOf(ReadEvent.Translated.class);
        return ((ReadEvent.Translated) event.outcome()).command();
    }

    private static Money amount(String value, CurrencyCode currency) {
        return new Money(new BigDecimal(value), currency);
    }

    // --- Translation (§7.1) -----------------------------------------------------------------

    @Test
    void AC08_validEventIsTranslatedToPostSettlement() {
        ReadEvent read = read(event());

        assertThat(command(read)).isEqualTo(new PostSettlement(
                "cbmm_20250909_000123",
                Instant.parse("2025-09-09T15:32:10Z"),
                AccountId.of("ACC123456789"),
                amount("15000.50", MXN),
                AccountId.of("ACC987654321"),
                amount("880.25", USD)));
        assertThat(read.eventId()).isEqualTo("cbmm_20250909_000123");
        assertThat(read.operationDate()).isEqualTo(Instant.parse("2025-09-09T15:32:10Z"));
        assertThat(read.hasIdentity()).isTrue();
    }

    @Test
    void AC08_amountsAreNeverRounded() {
        ReadEvent asNumber = read(event().origin("amount", "12345678901234567.89").destination("amount", "0.1"));
        ReadEvent asText = read(event().origin("amount", text("12345678901234567.89")).destination("amount", text("0.2")));

        assertThat(command(asNumber).originAmount().amount()).isEqualByComparingTo("12345678901234567.89");
        assertThat(command(asNumber).destinationAmount().amount()).isEqualByComparingTo("0.10");
        assertThat(command(asText).originAmount().amount()).isEqualByComparingTo("12345678901234567.89");
        assertThat(command(asText).destinationAmount().amount()).isEqualByComparingTo("0.20");
    }

    @Test
    void AC08_trailingZerosAreNotDecimals() {
        assertThat(command(read(event().origin("amount", "10.500"))).originAmount()).isEqualTo(amount("10.50", MXN));
        assertThat(command(read(event().origin("amount", text("10.500"))))
                .originAmount()).isEqualTo(amount("10.50", MXN));
        assertThat(command(read(event().origin("amount", "1e2"))).originAmount()).isEqualTo(amount("100.00", MXN));
        assertThat(command(read(event().origin("amount", text("1E+2"))))
                .originAmount()).isEqualTo(amount("100.00", MXN));
        assertThat(command(read(event().origin("amount", "10000000000000000.000"))).originAmount())
                .isEqualTo(amount("10000000000000000.00", MXN));
    }

    @Test
    void AC08_dateWithOffsetIsStoredAsUtcInstant() {
        ReadEvent read = read(event().set("operation_date", text("2025-09-09T10:32:10-05:00")));

        assertThat(command(read).operationDate()).isEqualTo(Instant.parse("2025-09-09T15:32:10Z"));
    }

    @Test
    void additionalFieldsAreIgnored() {
        ReadEvent read = read(event().set("trace", "{\"a\":[1,2]}").origin("bank", text("X")));

        assertThat(read.outcome()).isInstanceOf(ReadEvent.Translated.class);
    }

    // --- AC08: one detail per invalid event -------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00", "-0", "\"0\"", "\"-5.00\""})
    void AC08_nonPositiveAmount(String amount) {
        assertThat(read(event().origin("amount", amount)).outcome()).isEqualTo(invalid("NON_POSITIVE_AMOUNT"));
        assertThat(read(event().destination("amount", amount)).outcome()).isEqualTo(invalid("NON_POSITIVE_AMOUNT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.005", "\"10.005\"", "0.001", "1E-999999999", "\"1E-999999999\""})
    void AC08_tooManyDecimals_isRejectedNotRounded(String amount) {
        assertThat(read(event().origin("amount", amount)).outcome()).isEqualTo(invalid("TOO_MANY_DECIMALS"));
        assertThat(read(event().destination("amount", amount)).outcome()).isEqualTo(invalid("TOO_MANY_DECIMALS"));
    }

    @Test
    void AC08_sameCurrency() {
        ReadEvent read = read(event().destination("currency", text("MXN")));

        assertThat(read.outcome()).isEqualTo(invalid("SAME_CURRENCY"));
    }

    @Test
    void AC08_missingField() {
        assertThat(read(event().set("event_id", null)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().set("operation_date", null)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().set("origin", "null")).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().origin("account_id", null)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().origin("currency", "null")).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().origin("amount", null)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().destination("account_id", text(" "))).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().destination("amount", null)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().set("destination", null)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
    }

    /** §7.2: a null field or a blank text counts as absent, in every field. */
    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"   \"", "null"})
    void AC08_missingField_blankOrNullTextInAnyField(String blank) {
        assertThat(read(event().set("event_id", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().set("operation_date", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().set("origin", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().set("destination", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().origin("account_id", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().origin("currency", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().origin("amount", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().destination("account_id", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().destination("currency", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().destination("amount", blank)).outcome()).isEqualTo(invalid("MISSING_FIELD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2025-09-09 15:32:10\"", "\"2025-09-09T15:32:10\"", "\"2025-13-01T00:00:00Z\"",
        "\"09/09/2025\"", "20250909", "true"})
    void AC08_invalidDate(String date) {
        ReadEvent read = read(event().set("operation_date", date));

        assertThat(read.outcome()).isEqualTo(invalid("INVALID_DATE"));
        assertThat(read.operationDate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"EUR\"", "\"mxn\"", "\"MX\"", "\" MXN\""})
    void AC08_unsupportedCurrency(String currency) {
        assertThat(read(event().origin("currency", currency)).outcome()).isEqualTo(invalid("UNSUPPORTED_CURRENCY"));
        assertThat(read(event().destination("currency", currency)).outcome())
                .isEqualTo(invalid("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void AC08_fixtureF04InvalidEventsGetTheirDetailAndValidOnesAreTranslated() {
        EventFile file = reader.read("F04-invalid-events.json",
                fixture("F04-validation-rejections/loads/1/F04-invalid-events.json"));

        assertThat(file.status()).isEqualTo(FileStatus.PROCESSED);
        Map<String, ReadEvent.Outcome> outcomes = file.events().stream()
                .collect(Collectors.toMap(ReadEvent::eventId, ReadEvent::outcome));
        assertThat(outcomes).hasSize(10);
        assertThat(outcomes.get("cbmm_20250909_000402")).isEqualTo(invalid("NON_POSITIVE_AMOUNT"));
        assertThat(outcomes.get("cbmm_20250909_000403")).isEqualTo(invalid("NON_POSITIVE_AMOUNT"));
        assertThat(outcomes.get("cbmm_20250909_000404")).isEqualTo(invalid("TOO_MANY_DECIMALS"));
        assertThat(outcomes.get("cbmm_20250909_000405")).isEqualTo(invalid("SAME_CURRENCY"));
        assertThat(outcomes.get("cbmm_20250909_000406")).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(outcomes.get("cbmm_20250909_000407")).isEqualTo(invalid("INVALID_DATE"));
        assertThat(outcomes.get("cbmm_20250909_000408")).isEqualTo(invalid("UNSUPPORTED_CURRENCY"));
        assertThat(outcomes.get("cbmm_20250909_000409"))
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.UNSUPPORTED_EVENT_TYPE, null));
        assertThat(((ReadEvent.Translated) outcomes.get("cbmm_20250909_000401")).command().originAmount())
                .isEqualTo(amount("100.00", MXN));
        assertThat(((ReadEvent.Translated) outcomes.get("cbmm_20250909_000410")).command().originAmount())
                .isEqualTo(amount("10.50", MXN));
        assertThat(file.events()).extracting(ReadEvent::position).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    // --- AC09 -------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"\"cross_border_refund\"", "\"CROSS_BORDER_MONEY_MOVEMENT\"", "\"\"", "7", "null",
        "{\"type\":\"cross_border_money_movement\"}"})
    void AC09_unsupportedEventType(String eventType) {
        ReadEvent read = read(event().set("event_type", eventType));

        assertThat(read.outcome()).isEqualTo(new ReadEvent.Invalid(RejectionCode.UNSUPPORTED_EVENT_TYPE, null));
        assertThat(read.eventId()).isEqualTo("cbmm_20250909_000123");
        assertThat(read.hasIdentity()).isTrue();
    }

    @Test
    void AC09_missingEventTypeIsUnsupported() {
        assertThat(read(event().set("event_type", null)).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.UNSUPPORTED_EVENT_TYPE, null));
    }

    @Test
    void AC09_ruleOneWinsOverRuleTwo() {
        ReadEvent read = read(event().set("event_type", text("other")).set("destination", null).origin("amount", "0"));

        assertThat(read.outcome()).isEqualTo(new ReadEvent.Invalid(RejectionCode.UNSUPPORTED_EVENT_TYPE, null));
    }

    @Test
    void AC09_restOfFileIsTranslated() {
        String content = "[" + event().set("event_id", text("e1")).set("event_type", text("other")).json() + ","
                + event().set("event_id", text("e2")).json() + "]";

        EventFile file = reader.read("a.json", content.getBytes(StandardCharsets.UTF_8));

        assertThat(file.events().get(0).outcome())
                .isEqualTo(new ReadEvent.Invalid(RejectionCode.UNSUPPORTED_EVENT_TYPE, null));
        assertThat(command(file.events().get(1)).sourceEventId()).isEqualTo("e2");
    }

    // --- INVALID_FORMAT (§7.2) --------------------------------------------------------------

    @Test
    void invalidFormat_examplesOfTheSpec() {
        assertThat(read(event().origin("amount", text("abc"))).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
        assertThat(read(event().origin("amount", "true")).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
        assertThat(read(event().origin("account_id", "123456789")).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
        assertThat(read(event().set("origin", text("ACC123456789"))).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\" 10\"", "\"10 \"", "\"+10\"", "\".5\"", "\"5.\"", "\"007\"", "\"1,000.00\"",
        "\"-\"", "\"1e\"", "\"NaN\"", "{}", "[10]"})
    void invalidFormat_amountThatIsNotAJsonNumber(String amount) {
        assertThat(read(event().destination("amount", amount)).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
    }

    @Test
    void invalidFormat_otherFields() {
        assertThat(read(event().set("event_id", "123")).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
        assertThat(read(event().set("destination", "[]")).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
        assertThat(read(event().origin("currency", "1")).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
        assertThat(read(event().destination("account_id", "{}")).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
    }

    // --- Limits: FIELD_TOO_LONG and AMOUNT_TOO_LARGE (§7.2) ---------------------------------

    @Test
    void fieldTooLong_accountIdAt64And65() {
        assertThat(read(event().origin("account_id", text("A".repeat(64)))).outcome())
                .isInstanceOf(ReadEvent.Translated.class);
        assertThat(read(event().origin("account_id", text("A".repeat(65)))).outcome())
                .isEqualTo(invalid("FIELD_TOO_LONG"));
        assertThat(read(event().destination("account_id", text("B".repeat(65)))).outcome())
                .isEqualTo(invalid("FIELD_TOO_LONG"));
    }

    @Test
    void fieldTooLong_countsCharactersNotUtf16Units() {
        String emoji = "💰";

        assertThat(read(event().origin("account_id", text(emoji.repeat(64)))).outcome())
                .isInstanceOf(ReadEvent.Translated.class);
        assertThat(read(event().origin("account_id", text(emoji.repeat(65)))).outcome())
                .isEqualTo(invalid("FIELD_TOO_LONG"));
    }

    @Test
    void fieldTooLong_eventIdAt128And129_withoutIdentity() {
        ReadEvent at128 = read(event().set("event_id", text("e".repeat(128))));
        ReadEvent at129 = read(event().set("event_id", text("e".repeat(129))));

        assertThat(command(at128).sourceEventId()).hasSize(128);
        assertThat(at128.hasIdentity()).isTrue();
        assertThat(at129.outcome()).isEqualTo(invalid("FIELD_TOO_LONG"));
        assertThat(at129.eventId()).hasSize(129);
        assertThat(at129.hasIdentity()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"99999999999999999.99", "\"99999999999999999.99\"", "10000000000000000", "1E+16"})
    void amountTooLarge_17IntegerDigitsAreAccepted(String amount) {
        assertThat(read(event().origin("amount", amount)).outcome()).isInstanceOf(ReadEvent.Translated.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"100000000000000000", "\"100000000000000000.00\"", "1E+17", "1E+999999999",
        "\"1E+999999999\"", "\"1e99999999999999999999999\""})
    void amountTooLarge_18IntegerDigitsOrMoreAreRejected(String amount) {
        assertThat(read(event().origin("amount", amount)).outcome()).isEqualTo(invalid("AMOUNT_TOO_LARGE"));
        assertThat(read(event().destination("amount", amount)).outcome()).isEqualTo(invalid("AMOUNT_TOO_LARGE"));
    }

    @Test
    void amountAsHugeText_isCheckedInLinearTime() {
        Event manyZerosAfterPoint = event().origin("amount", text("1." + "0".repeat(1_000_000)));
        Event manyIntegerDigits = event().origin("amount", text("1" + "0".repeat(1_000_000)));
        Event manyDecimals = event().origin("amount", text("0." + "0".repeat(1_000_000) + "1"));

        assertTimeoutPreemptively(LINEAR, () -> {
            assertThat(command(read(manyZerosAfterPoint)).originAmount()).isEqualTo(amount("1.00", MXN));
            assertThat(read(manyIntegerDigits).outcome()).isEqualTo(invalid("AMOUNT_TOO_LARGE"));
            assertThat(read(manyDecimals).outcome()).isEqualTo(invalid("TOO_MANY_DECIMALS"));
        });
    }

    // --- Precedence inside rule 2 (spec §6.1 field order) -----------------------------------

    @Test
    void precedence_followsFieldOrderOfTheEvent() {
        assertThat(read(event().set("event_id", null).set("operation_date", text("x"))).outcome())
                .isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().set("operation_date", text("x")).origin("currency", text("EUR"))).outcome())
                .isEqualTo(invalid("INVALID_DATE"));
        assertThat(read(event().origin("amount", "10.005").destination("account_id", null)).outcome())
                .isEqualTo(invalid("TOO_MANY_DECIMALS"));
        assertThat(read(event().origin("account_id", null).origin("amount", "0")).outcome())
                .isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().origin("currency", text("EUR")).origin("amount", "0")).outcome())
                .isEqualTo(invalid("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void precedence_insideAnAmount() {
        assertThat(read(event().origin("amount", "-10.005")).outcome()).isEqualTo(invalid("NON_POSITIVE_AMOUNT"));
        assertThat(read(event().origin("amount", "123456789012345678.005")).outcome())
                .isEqualTo(invalid("TOO_MANY_DECIMALS"));
    }

    @Test
    void precedence_sameCurrencyIsCheckedAfterBothLegs() {
        ReadEvent read = read(event().destination("currency", text("MXN")).destination("amount", "0"));

        assertThat(read.outcome()).isEqualTo(invalid("NON_POSITIVE_AMOUNT"));
    }

    // --- Amount edge cases (ledger review of AmountText) -----------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"10.005E1", "\"10.005E1\""})
    void amount_exponentWithFraction_isExact(String amount) {
        assertThat(command(read(event().origin("amount", amount))).originAmount()).isEqualTo(amount("100.05", MXN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5E-2", "\"0.5E-2\""})
    void amount_exponentWithFraction_tooManyDecimals(String amount) {
        assertThat(read(event().origin("amount", amount)).outcome()).isEqualTo(invalid("TOO_MANY_DECIMALS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "\"0.01\""})
    void amount_minimumIsOneCent(String amount) {
        assertThat(command(read(event().origin("amount", amount))).originAmount()).isEqualTo(amount("0.01", MXN));
    }

    @Test
    void amount_invalidFormatWinsOverNonPositive() {
        assertThat(read(event().origin("amount", text("-abc"))).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
        assertThat(read(event().origin("amount", text("-007"))).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1E+999999999", "\"-1E+999999999\""})
    void amount_nonPositiveWinsOverTooLarge(String amount) {
        assertThat(read(event().origin("amount", amount)).outcome()).isEqualTo(invalid("NON_POSITIVE_AMOUNT"));
    }

    /** Fullwidth digits, Arabic-Indic digits and the Unicode minus sign are not JSON number text. */
    @ParameterizedTest
    @ValueSource(strings = {"\"１０\"", "\"١٠\"", "\"−5\"", "\"1．50\""})
    void amount_nonAsciiDigitsAreInvalidFormat(String amount) {
        assertThat(read(event().origin("amount", amount)).outcome()).isEqualTo(invalid("INVALID_FORMAT"));
    }

    /** Scale exactly at the edge of int: parsed by Jackson and classified without expanding it. */
    @Test
    void amount_scaleAtTheEdgeOfInt() {
        assertTimeoutPreemptively(LINEAR, () -> {
            assertThat(read(event().origin("amount", "1E-2147483647")).outcome())
                    .isEqualTo(invalid("TOO_MANY_DECIMALS"));
            assertThat(read(event().origin("amount", "1E+2147483647")).outcome())
                    .isEqualTo(invalid("AMOUNT_TOO_LARGE"));
        });
    }

    // --- What an invalid event still exposes for ordering and identity (§7.2, §7.3) ---------

    /** §7.2: an event_id that is not text is INVALID_FORMAT and cannot be stored as identity. */
    @ParameterizedTest
    @ValueSource(strings = {"42", "true", "{\"id\":\"e1\"}", "[\"e1\"]"})
    void eventIdThatIsNotText_isInvalidFormatWithoutIdentity(String eventId) {
        ReadEvent read = read(event().set("event_id", eventId));

        assertThat(read.outcome()).isEqualTo(invalid("INVALID_FORMAT"));
        assertThat(read.hasIdentity()).isFalse();
        assertThat(read.eventId()).isNull();
    }

    /** §7.2: identity decides whether a decision is stored, not the code; rule 1 still goes first. */
    @Test
    void unsupportedEventTypeWithoutEventId_isUnsupportedWithoutIdentity() {
        ReadEvent read = read(event().set("event_type", text("other")).set("event_id", null));

        assertThat(read.outcome()).isEqualTo(new ReadEvent.Invalid(RejectionCode.UNSUPPORTED_EVENT_TYPE, null));
        assertThat(read.hasIdentity()).isFalse();
    }

    @Test
    void invalidEventKeepsItsIdAndDateForOrdering() {
        ReadEvent read = read(event().origin("currency", text("EUR")));

        assertThat(read.eventId()).isEqualTo("cbmm_20250909_000123");
        assertThat(read.operationDate()).isEqualTo(Instant.parse("2025-09-09T15:32:10Z"));
        assertThat(read.hasIdentity()).isTrue();
    }

    @Test
    void eventWithoutUsableIdHasNoIdentity() {
        assertThat(read(event().set("event_id", null)).hasIdentity()).isFalse();
        assertThat(read(event().set("event_id", text("  "))).hasIdentity()).isFalse();
        assertThat(read(event().set("event_id", text("  "))).outcome()).isEqualTo(invalid("MISSING_FIELD"));
        assertThat(read(event().set("event_id", "42")).hasIdentity()).isFalse();
        assertThat(read(event().set("event_id", null)).eventId()).isNull();
    }

    private static byte[] fixture(String path) {
        try (InputStream in = CbmmEventTranslatorTest.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
