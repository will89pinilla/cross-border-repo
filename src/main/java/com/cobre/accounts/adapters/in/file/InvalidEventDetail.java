package com.cobre.accounts.adapters.in.file;

/** Details of {@code INVALID_EVENT} (spec §7.2, rule 2). Stored as text in {@code reason_detail}. */
enum InvalidEventDetail {
    MALFORMED_JSON,
    MISSING_FIELD,
    /** {@code event_id} with the {@code opening_} prefix of the opening entries (spec §6.3). */
    RESERVED_EVENT_ID,
    INVALID_FORMAT,
    INVALID_DATE,
    UNSUPPORTED_CURRENCY,
    NON_POSITIVE_AMOUNT,
    TOO_MANY_DECIMALS,
    SAME_CURRENCY,
    AMOUNT_TOO_LARGE,
    FIELD_TOO_LONG
}
