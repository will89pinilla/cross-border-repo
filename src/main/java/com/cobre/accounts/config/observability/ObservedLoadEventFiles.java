package com.cobre.accounts.config.observability;

import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.FileStatus;
import com.cobre.accounts.application.port.in.LoadEventFilesUseCase;
import com.cobre.accounts.application.port.in.LoadResult;
import com.cobre.accounts.application.port.in.LoadResult.EventResult;
import com.cobre.accounts.application.port.in.LoadResult.LoadedFile;
import com.cobre.accounts.application.port.in.SettlementOutcome.Applied;
import com.cobre.accounts.application.port.in.SettlementOutcome.Duplicate;
import com.cobre.accounts.application.port.in.SettlementOutcome.Rejected;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Spec §11 (D15), per load: {@code cbmm_load_duration_seconds}, {@code cbmm_events_total} with
 * {@code result} ({@code applied}, {@code rejected}, {@code duplicate}) and {@code code} (the
 * rejection code, or {@code none}), and {@code cbmm_files_total} with {@code status}. Counted from
 * the {@link LoadResult}, the only place that has every event: duplicates inside a load are never
 * posted (§7.5). A load that is refused (409) or fails records nothing. No tag carries an
 * {@code account_id}. Logs, with {@code load_id}: a WARN per {@code UNREADABLE} file (with
 * {@code file}) and a summary line with the counts of the report.
 *
 * <p>A decorator in {@code config}, so that {@code application} stays free of Micrometer and SLF4J
 * (spec §11, ArchUnit).
 */
public final class ObservedLoadEventFiles implements LoadEventFilesUseCase {

    static final String EVENTS = "cbmm.events";
    static final String FILES = "cbmm.files";
    static final String LOAD_DURATION = "cbmm.load.duration";
    static final String NO_CODE = "none";

    private static final Logger log = LoggerFactory.getLogger(ObservedLoadEventFiles.class);

    private final LoadEventFilesUseCase delegate;
    private final MeterRegistry registry;
    private final Timer loadDuration;

    public ObservedLoadEventFiles(LoadEventFilesUseCase delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.loadDuration = Timer.builder(LOAD_DURATION)
                .description("Duration of a load, from the lock to the report (spec §7.3)")
                .register(registry);
    }

    @Override
    public LoadResult load(Supplier<List<EventFile>> readFiles) {
        long start = System.nanoTime();
        LoadResult result = delegate.load(readFiles);
        loadDuration.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        for (EventResult event : result.events()) {
            eventCounter(event).increment();
        }
        for (LoadedFile file : result.files()) {
            Counter.builder(FILES)
                    .description("Files of the loads, by status (spec §8.1)")
                    .tag("status", file.status().name().toLowerCase(Locale.ROOT))
                    .register(registry)
                    .increment();
        }
        try (var ignored = MDC.putCloseable("load_id", result.loadId().toString())) {
            for (LoadedFile file : result.files()) {
                if (file.status() == FileStatus.UNREADABLE) {
                    try (var named = MDC.putCloseable("file", file.name())) {
                        log.warn("File {} is UNREADABLE: none of its events was read (spec §6.2)", file.name());
                    }
                }
            }
            log.info("Load finished: {} file(s), {} event(s) read, {} applied, {} rejected, {} duplicate(s)",
                    result.files().size(), result.eventsRead(), result.applied(), result.rejected(),
                    result.duplicates());
        }
        return result;
    }

    private Counter eventCounter(EventResult event) {
        String outcome;
        String code;
        switch (event.outcome()) {
            case Applied applied -> {
                outcome = "applied";
                code = NO_CODE;
            }
            case Rejected rejected -> {
                outcome = "rejected";
                code = rejected.code().name();
            }
            case Duplicate duplicate -> {
                outcome = "duplicate";
                code = NO_CODE;
            }
        }
        return Counter.builder(EVENTS)
                .description("Events of the loads, by result and rejection code (spec §11)")
                .tag("result", outcome)
                .tag("code", code)
                .register(registry);
    }
}
