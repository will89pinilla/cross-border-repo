package com.cobre.accounts.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Writes a benchmark result to {@code build/reports/benchmark} and to the console, with the hardware. */
final class BenchmarkReport {

    private BenchmarkReport() {}

    static void write(String fileName, String markdown) {
        Path dir = Path.of(System.getProperty("benchmark.reportDir", "build/reports/benchmark"));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), markdown + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(markdown);
        System.out.println("Written to " + dir.resolve(fileName).toAbsolutePath());
    }

    static String hardware() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return String.format(Locale.ROOT, """
                Hardware: %s, %d cores, %.0f GB RAM; %s %s (%s).
                JVM: %s %s, max heap %,d MB.
                PostgreSQL 18 in Testcontainers (Docker) on the same machine.""",
                cpu(), Runtime.getRuntime().availableProcessors(), os.getTotalMemorySize() / 1e9,
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                System.getProperty("java.vm.name"), System.getProperty("java.version"),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    private static String cpu() {
        try {
            if (System.getProperty("os.name").startsWith("Mac")) {
                Process sysctl = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
                return new String(sysctl.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            Path cpuinfo = Path.of("/proc/cpuinfo");
            if (Files.exists(cpuinfo)) {
                return Files.readAllLines(cpuinfo).stream().filter(l -> l.startsWith("model name"))
                        .map(l -> l.substring(l.indexOf(':') + 1).trim()).findFirst().orElse("unknown CPU");
            }
        } catch (IOException e) {
            return "unknown CPU";
        }
        return "unknown CPU";
    }
}
