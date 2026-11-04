package com.cobre.accounts.adapters.in.file;

/** Details of {@code INVALID_EVENT} (spec §7.2, rule 2). Stored as text in {@code reason_detail}. */
enum InvalidEventDetail {
    MALFORMED_JSON,
    MISSING_FIELD,
    INVALID_FORMAT,
    INVALID_DATE,
    UNSUPPORTED_CURRENCY,
    NON_POSITIVE_AMOUNT,
    TOO_MANY_DECIMALS,
    SAME_CURRENCY,
    AMOUNT_TOO_LARGE,
    FIELD_TOO_LONG
}
