package com.cobre.accounts.config.observability;

import com.cobre.accounts.application.port.in.PostSettlementUseCase;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.application.port.in.SettlementOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

/**
 * Spec §11 (D15), per event: {@code load_id}, {@code event_id} (absent when the event has none),
 * {@code file} and {@code position} in the MDC of the thread that posts it, so every log line of
 * the posting (among them {@code EntriesPosted} and {@code PostingRejected}, §8.3) carries them in
 * the JSON and a rejection can be found in its file; and {@code cbmm_event_posting_seconds},
 * failed postings included. The thread's previous MDC values are restored afterwards.
 */
public final class ObservedPostSettlement implements PostSettlementUseCase {

    static final String EVENT_POSTING = "cbmm.event.posting";
    static final String LOAD_ID = "load_id";
    static final String EVENT_ID = "event_id";
    /** Where the event is in the input, as {@code file} and {@code position} of the report (§8.1). */
    static final String FILE = "file";
    static final String POSITION = "position";
    private static final List<String> KEYS = List.of(LOAD_ID, EVENT_ID, FILE, POSITION);

    private final PostSettlementUseCase delegate;
    private final Timer posting;

    public ObservedPostSettlement(PostSettlementUseCase delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.posting = Timer.builder(EVENT_POSTING)
                .description("Time to decide one event in its transaction, retries included (spec §7.3)")
                .register(Objects.requireNonNull(registry, "registry"));
    }

    @Override
    public SettlementOutcome post(ReadEvent event, UUID loadId) {
        Map<String, String> previous = new HashMap<>();
        for (String key : KEYS) {
            previous.put(key, MDC.get(key));
        }
        MDC.put(LOAD_ID, String.valueOf(loadId));
        put(EVENT_ID, event.eventId());
        MDC.put(FILE, event.fileName());
        MDC.put(POSITION, Integer.toString(event.position()));
        long start = System.nanoTime();
        try {
            return delegate.post(event, loadId);
        } finally {
            posting.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            previous.forEach(ObservedPostSettlement::put);
        }
    }

    /** Puts {@code value}, or removes the key when it is null. */
    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
