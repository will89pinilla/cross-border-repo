package com.cobre.accounts.application.port.in;

import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read model of {@code GET /v1/accounts/{id}/transactions} (spec §8.2): one page of the account's
 * entry lines, openings included, ordered by {@code posted_at}, {@code source_event_id} and line
 * number. Built from rows, never from {@code EntryLine}, which only exists with its {@code Account}.
 *
 * @param total all the lines of the account, not only this page's
 */
public record EntryLinePage(
        AccountId accountId, CurrencyCode currency, long total, int limit, int offset, List<Line> lines) {

    public EntryLinePage {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currency, "currency");
        lines = List.copyOf(lines);
    }

    /** One entry line as stored; {@code amount} is positive with scale 2. */
    public record Line(String sourceEventId, int lineNo, Direction direction, BigDecimal amount, Instant postedAt) {

        public Line {
            Objects.requireNonNull(sourceEventId, "sourceEventId");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(postedAt, "postedAt");
        }
    }
}
