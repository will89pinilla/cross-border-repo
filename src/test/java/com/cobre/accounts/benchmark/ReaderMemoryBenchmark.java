package com.cobre.accounts.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.accounts.adapters.in.file.CbmmEventTranslator;
import com.cobre.accounts.adapters.in.file.EventFileReader;
import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.support.SettlementScenarioGenerator;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Heap of the reader at the upload limits of spec §6.2 (security review of T07, availability): 1,000
 * files and 1,000,000 events, read in parallel with virtual threads as the controller does, without
 * database. The contents are held as {@code byte[]}, as {@code MultipartFile.getBytes()} gives them.
 * Run with {@code ./gradlew benchmark}; the heap is {@code -Pbenchmark.xmx} (2g by default).
 *
 * <p>Compact events take ~245 bytes, so 1,000,000 of them exceed the 200 MB per load: in practice
 * the 200 MB limit is reached first. Measuring 1,000,000 gives the upper bound of both limits.
 */
@Tag("benchmark")
class ReaderMemoryBenchmark {

    private static final int FILES = 1_000;
    private static final int EVENTS = 1_000_000;

    @Test
    void readerHeapAtTheUploadLimits() throws Exception {
        EventFileReader reader = new EventFileReader(
                new CbmmEventTranslator(Set.of(CurrencyCode.of("MXN"), CurrencyCode.of("USD"))));
        long baseline = usedAfterGc();

        Map<String, byte[]> files = SettlementScenarioGenerator.eventFiles(
                new SettlementScenarioGenerator(7L).generate(1_000, EVENTS), FILES);
        long inputBytes = files.values().stream().mapToLong(f -> f.length).sum();
        long withInput = usedAfterGc();

        resetPeaks();
        List<EventFile> read;
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<EventFile>> futures = files.entrySet().stream()
                    .map(f -> threads.submit(() -> reader.read(f.getKey(), f.getValue())))
                    .toList();
            read = futures.stream().map(ReaderMemoryBenchmark::get).toList();
        }
        long peak = peakUsed();
        long afterRead = usedAfterGc();

        long events = read.stream().mapToLong(f -> f.events().size()).sum();
        assertThat(read).hasSize(FILES);
        assertThat(events).isGreaterThanOrEqualTo(EVENTS);
        long max = Runtime.getRuntime().maxMemory();
        BenchmarkReport.write("reader-memory.md", String.join("\n", List.of(
                "# Reader heap at the limits of §6.2",
                "",
                String.format(Locale.ROOT, "%,d files, %,d events read (with the F07 duplicates), %,.1f MB of input.",
                        FILES, events, inputBytes / 1e6),
                "",
                "| Measure | MB |",
                "| --- | --- |",
                row("Max heap (-Xmx)", max),
                row("Used before generating the files (after GC)", baseline),
                row("Used with the input bytes in memory (after GC)", withInput),
                row("Peak while reading (sum of heap pool peaks, upper bound)", peak),
                row("Used after reading, input and events retained (after GC)", afterRead),
                row("Retained by the events read (after - with input)", afterRead - withInput),
                "",
                String.format(Locale.ROOT, "Per event retained: %,d bytes; input per event: %,d bytes.",
                        (afterRead - withInput) / events, inputBytes / events),
                "",
                BenchmarkReport.hardware())));
        // Keep the files and events reachable until here, so the measures above are what a load holds.
        assertThat(files).hasSize(FILES);
    }

    private static String row(String label, long bytes) {
        return String.format(Locale.ROOT, "| %s | %,.0f |", label, bytes / (1024.0 * 1024.0));
    }

    private static EventFile get(Future<EventFile> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private static long usedAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void resetPeaks() {
        heapPools().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private static long peakUsed() {
        return heapPools().stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
    }

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP).toList();
    }
}
