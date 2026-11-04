package com.cobre.accounts.adapters.in.file;

import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.PostSettlement;
import com.cobre.accounts.domain.model.RejectionCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Anti-corruption layer: evaluates rules 1 and 2 of spec §7.2 on a CBMM event and translates a
 * valid one into {@link PostSettlement} (§7.1).
 *
 * <p>Inside rule 2 the detail is the one of the first field that fails, in the field order of §6.1
 * ({@code event_id}, {@code operation_date}, {@code origin}, {@code destination}), and then
 * {@code SAME_CURRENCY}. Inside a leg: {@code account_id}, {@code currency}, {@code amount}. A JSON
 * {@code null} or a blank text counts as absent ({@code MISSING_FIELD}).
 */
public final class CbmmEventTranslator {

    static final String EVENT_TYPE = "cross_border_money_movement";
    static final int MAX_EVENT_ID_LENGTH = 128;
    static final int MAX_ACCOUNT_ID_LENGTH = 64;

    private final Set<String> supportedCurrencies;

    /** @param supportedCurrencies {@code cbmm.currencies} (spec §6.1) */
    public CbmmEventTranslator(Set<CurrencyCode> supportedCurrencies) {
        this.supportedCurrencies = supportedCurrencies.stream()
                .map(CurrencyCode::value)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** A line or array element that is not a JSON object (§6.2). */
    ReadEvent malformed(String fileName, int position) {
        return new ReadEvent(fileName, position, null, null, false, invalid(InvalidEventDetail.MALFORMED_JSON));
    }

    ReadEvent translate(String fileName, int position, JsonNode event) {
        Objects.requireNonNull(event, "event");
        if (!event.isObject()) {
            return malformed(fileName, position);
        }
        JsonNode eventIdNode = present(event, "event_id");
        String eventId = eventIdNode != null && eventIdNode.isString() ? eventIdNode.stringValue() : null;
        boolean hasIdentity = eventId != null && !eventId.isBlank() && length(eventId) <= MAX_EVENT_ID_LENGTH;
        Instant operationDate = parseDate(present(event, "operation_date"));

        ReadEvent.Outcome outcome = evaluate(event, eventIdNode, eventId, operationDate);
        return new ReadEvent(fileName, position, eventId, operationDate, hasIdentity, outcome);
    }

    private ReadEvent.Outcome evaluate(JsonNode event, JsonNode eventIdNode, String eventId, Instant operationDate) {
        // Rule 1: absent or not text is not cross_border_money_movement either.
        JsonNode eventType = present(event, "event_type");
        if (eventType == null || !eventType.isString() || !EVENT_TYPE.equals(eventType.stringValue())) {
            return new ReadEvent.Invalid(RejectionCode.UNSUPPORTED_EVENT_TYPE, null);
        }
        // Rule 2, in field order.
        if (eventIdNode == null) {
            return invalid(InvalidEventDetail.MISSING_FIELD);
        }
        if (eventId == null) {
            return invalid(InvalidEventDetail.INVALID_FORMAT);
        }
        if (length(eventId) > MAX_EVENT_ID_LENGTH) {
            return invalid(InvalidEventDetail.FIELD_TOO_LONG);
        }
        if (present(event, "operation_date") == null) {
            return invalid(InvalidEventDetail.MISSING_FIELD);
        }
        if (operationDate == null) {
            return invalid(InvalidEventDetail.INVALID_DATE);
        }
        LegResult origin = leg(present(event, "origin"));
        if (origin instanceof LegFailed failed) {
            return invalid(failed.detail());
        }
        LegResult destination = leg(present(event, "destination"));
        if (destination instanceof LegFailed failed) {
            return invalid(failed.detail());
        }
        Leg from = (Leg) origin;
        Leg to = (Leg) destination;
        if (from.amount().currency().equals(to.amount().currency())) {
            return invalid(InvalidEventDetail.SAME_CURRENCY);
        }
        return new ReadEvent.Translated(
                new PostSettlement(eventId, operationDate, from.accountId(), from.amount(), to.accountId(), to.amount()));
    }

    private sealed interface LegResult {}

    private record Leg(AccountId accountId, Money amount) implements LegResult {}

    private record LegFailed(InvalidEventDetail detail) implements LegResult {}

    private LegResult leg(JsonNode leg) {
        if (leg == null) {
            return new LegFailed(InvalidEventDetail.MISSING_FIELD);
        }
        if (!leg.isObject()) {
            return new LegFailed(InvalidEventDetail.INVALID_FORMAT);
        }
        JsonNode accountId = present(leg, "account_id");
        if (accountId == null) {
            return new LegFailed(InvalidEventDetail.MISSING_FIELD);
        }
        if (!accountId.isString()) {
            return new LegFailed(InvalidEventDetail.INVALID_FORMAT);
        }
        if (length(accountId.stringValue()) > MAX_ACCOUNT_ID_LENGTH) {
            return new LegFailed(InvalidEventDetail.FIELD_TOO_LONG);
        }
        JsonNode currency = present(leg, "currency");
        if (currency == null) {
            return new LegFailed(InvalidEventDetail.MISSING_FIELD);
        }
        if (!currency.isString()) {
            return new LegFailed(InvalidEventDetail.INVALID_FORMAT);
        }
        if (!supportedCurrencies.contains(currency.stringValue())) {
            return new LegFailed(InvalidEventDetail.UNSUPPORTED_CURRENCY);
        }
        JsonNode amount = present(leg, "amount");
        if (amount == null) {
            return new LegFailed(InvalidEventDetail.MISSING_FIELD);
        }
        AmountText.Result parsed;
        if (amount.isString()) {
            parsed = AmountText.parse(amount.stringValue());
        } else if (amount.isNumber()) {
            // Floats are read as BigDecimal from the JSON text (never double); toString keeps a huge
            // exponent in scientific notation, so it is not expanded.
            parsed = AmountText.parse(amount.decimalValue().toString());
        } else {
            return new LegFailed(InvalidEventDetail.INVALID_FORMAT);
        }
        return switch (parsed) {
            case AmountText.Failed failed -> new LegFailed(failed.detail());
            case AmountText.Valid valid -> new Leg(AccountId.of(accountId.stringValue()),
                    new Money(valid.amount(), CurrencyCode.of(currency.stringValue())));
        };
    }

    /** The field, or null when it counts as absent: missing, JSON {@code null} or blank text (§7.2). */
    private static JsonNode present(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull() || (value.isString() && value.stringValue().isBlank())) {
            return null;
        }
        return value;
    }

    /** ISO 8601 with zone ({@code Z} or offset), as an instant in UTC (§6.1). Null when not valid. */
    private static Instant parseDate(JsonNode date) {
        if (date == null || !date.isString()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(date.stringValue(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Characters as PostgreSQL varchar counts them (code points), not UTF-16 units. */
    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static ReadEvent.Invalid invalid(InvalidEventDetail detail) {
        return new ReadEvent.Invalid(RejectionCode.INVALID_EVENT, detail.name());
    }
}
