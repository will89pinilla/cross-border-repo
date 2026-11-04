package com.cobre.accounts.adapters.out.events;

import com.cobre.accounts.application.port.out.DomainEventPublisher;
import com.cobre.accounts.domain.event.DomainEvent;
import com.cobre.accounts.domain.event.EntriesPosted;
import com.cobre.accounts.domain.event.PostingRejected;
import com.cobre.accounts.domain.model.EntryLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec §8.3: in the Task 2 the result events are written to the log as one JSON object each; in the
 * whole system they would go to Component A. Amounts are text with 2 decimals, as in the report.
 * Never throws: the decision is already committed (see {@link DomainEventPublisher}).
 */
public final class LoggingDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public void publish(DomainEvent event) {
        try {
            log.info("{}", toJson(event));
        } catch (RuntimeException e) {
            log.error("Could not emit {} for event {}", event.getClass().getSimpleName(), event.sourceEventId(), e);
        }
    }

    String toJson(DomainEvent event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("event", event.getClass().getSimpleName());
        json.put("source_event_id", event.sourceEventId());
        switch (event) {
            case EntriesPosted posted -> {
                json.put("entry_id", posted.entryId().value().toString());
                List<Map<String, Object>> lines = new ArrayList<>();
                int lineNo = 1;
                for (EntryLine line : posted.lines()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("line_no", lineNo++);
                    row.put("account_id", line.accountId().value());
                    row.put("direction", line.direction().name());
                    row.put("amount", line.amount().amount().toPlainString());
                    row.put("currency", line.currency().value());
                    row.put("posted_at", line.postedAt().toString());
                    lines.add(row);
                }
                json.put("lines", lines);
            }
            case PostingRejected rejected -> {
                json.put("code", rejected.code().name());
                json.put("detail", rejected.detail());
            }
        }
        return mapper.writeValueAsString(json);
    }
}
