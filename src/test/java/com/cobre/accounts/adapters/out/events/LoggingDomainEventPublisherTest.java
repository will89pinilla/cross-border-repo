package com.cobre.accounts.adapters.out.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cobre.accounts.domain.event.EntriesPosted;
import com.cobre.accounts.domain.event.PostingRejected;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Direction;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.RejectionCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec §8.3: in the Task 2 the result events are written to the log as JSON. */
class LoggingDomainEventPublisherTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Instant AT = Instant.parse("2025-09-09T15:32:10Z");

    private final LoggingDomainEventPublisher publisher = new LoggingDomainEventPublisher();

    @Test
    void entriesPostedIsJsonWithItsLinesAndAmountsAsText() {
        EntryId entryId = new EntryId(UUID.fromString("8f6d2c1e-0b7a-4c7e-9a51-2f0d6b9e4a10"));
        Account origin = Account.client(AccountId.of("ACC123456789"), MXN, AccountStatus.ACTIVE, Money.of("1.00", MXN));
        Account destination = Account.client(AccountId.of("ACC987654321"), USD, AccountStatus.ACTIVE, Money.of("0.00", USD));
        List<EntryLine> lines = List.of(
                EntryLine.of(origin, Direction.DEBIT, Money.of("15000.50", MXN), AT),
                EntryLine.of(Account.fxPosition(MXN, AccountStatus.ACTIVE), Direction.CREDIT, Money.of("15000.50", MXN), AT),
                EntryLine.of(Account.fxPosition(USD, AccountStatus.ACTIVE), Direction.DEBIT, Money.of("880.25", USD), AT),
                EntryLine.of(destination, Direction.CREDIT, Money.of("880.25", USD), AT));

        String json = publisher.toJson(new EntriesPosted("cbmm_20250909_000123", entryId, lines));

        assertThat(json).isEqualTo("{\"event\":\"EntriesPosted\",\"source_event_id\":\"cbmm_20250909_000123\","
                + "\"entry_id\":\"8f6d2c1e-0b7a-4c7e-9a51-2f0d6b9e4a10\",\"lines\":["
                + "{\"line_no\":1,\"account_id\":\"ACC123456789\",\"direction\":\"DEBIT\",\"amount\":\"15000.50\","
                + "\"currency\":\"MXN\",\"posted_at\":\"2025-09-09T15:32:10Z\"},"
                + "{\"line_no\":2,\"account_id\":\"COBRE_FX_POSITION_MXN\",\"direction\":\"CREDIT\",\"amount\":\"15000.50\","
                + "\"currency\":\"MXN\",\"posted_at\":\"2025-09-09T15:32:10Z\"},"
                + "{\"line_no\":3,\"account_id\":\"COBRE_FX_POSITION_USD\",\"direction\":\"DEBIT\",\"amount\":\"880.25\","
                + "\"currency\":\"USD\",\"posted_at\":\"2025-09-09T15:32:10Z\"},"
                + "{\"line_no\":4,\"account_id\":\"ACC987654321\",\"direction\":\"CREDIT\",\"amount\":\"880.25\","
                + "\"currency\":\"USD\",\"posted_at\":\"2025-09-09T15:32:10Z\"}]}");
    }

    @Test
    void postingRejectedIsJsonWithCodeAndDetail() {
        assertThat(publisher.toJson(new PostingRejected("e1", RejectionCode.CURRENCY_MISMATCH, "ORIGIN")))
                .isEqualTo("{\"event\":\"PostingRejected\",\"source_event_id\":\"e1\","
                        + "\"code\":\"CURRENCY_MISMATCH\",\"detail\":\"ORIGIN\"}");
        assertThat(publisher.toJson(new PostingRejected("e2", RejectionCode.INSUFFICIENT_FUNDS, null)))
                .isEqualTo("{\"event\":\"PostingRejected\",\"source_event_id\":\"e2\","
                        + "\"code\":\"INSUFFICIENT_FUNDS\",\"detail\":null}");
    }

    @Test
    void publishNeverThrows() {
        assertThatCode(() -> publisher.publish(new PostingRejected("e3", RejectionCode.INSUFFICIENT_FUNDS, null)))
                .doesNotThrowAnyException();
    }
}
