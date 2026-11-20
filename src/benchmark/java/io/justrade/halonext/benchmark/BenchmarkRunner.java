/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.benchmark;

import io.justrade.halonext.api.HaloDB;
import io.justrade.halonext.config.HaloDBOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * Entry point for the macro benchmark harness. Configuration is read from
 * {@code halo.bench.*} system properties (the {@code benchmarkRun} Gradle task maps
 * {@code -P} properties onto them). Engine options mirror the original HaloDB
 * benchmark presets: 1 GiB files, 50 percent compaction threshold, 10 MiB flush
 * buffer, index sized to 2x records, compaction capped at 135 MiB/s.
 *
 * <p>Numbers produced here are environment-sensitive and must not be treated as an
 * authoritative baseline on a WSL / overlay / shared dev box; run the MEDIUM or
 * RELEASE tier on a dedicated host for publication-grade results.
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {}

    public static void main(final String[] args) throws Exception {
        final Workload workload = Workload.parse(prop("workload", "FILL_SEQUENCE"));
        final DatasetTier tier = DatasetTier.parse(prop("tier", "SMOKE"));
        final long records = tier.records();
        final int valueSize = tier.valueSize();
        final int readThreads = Integer.parseInt(prop("threads", "32"));
        final long numberOfReads = Long.parseLong(prop("reads", Long.toString(records)));
        final long writeRate = Long.parseLong(prop("writeRate", "0"));
        final long durationSeconds = Long.parseLong(prop("duration", "60"));

        final String dirProp = System.getProperty("halo.bench.dir");
        final boolean ephemeral = dirProp == null;
        final Path dir = ephemeral ? Files.createTempDirectory("halo-next-macro") : Path.of(dirProp);
        Files.createDirectories(dir);

        System.out.printf(
                "workload=%s tier=%s records=%d valueSize=%d readThreads=%d reads=%d writeRate=%d dir=%s%n",
                workload, tier, records, valueSize, readThreads, numberOfReads, writeRate, dir);

        final HaloDBOptions options = new HaloDBOptions()
                .setMaxFileSize(maxFileSize(workload, records, valueSize))
                .setCompactionThresholdPerFile(0.50)
                .setFlushDataSizeBytes(10L * 1024 * 1024)
                .setNumberOfRecords(2 * records)
                .setCompactionJobRate(135L * 1024 * 1024)
                .setCompactionEnabled(true)
                .setSyncWrite(false);

        final DataGenerator gen = new DataGenerator(1 << 20, 100L);
        try (HaloDB db = HaloDB.open(dir, options)) {
            final MacroBenchmark bench =
                    new MacroBenchmark(db, dir, records, valueSize, readThreads, numberOfReads, writeRate, gen);
            final Map<String, Object> metrics = bench.run(workload, durationSeconds);
            BenchmarkReport.emit(
                    Path.of("build", "results", "benchmark"), workload.name() + "-" + tier.name(), metrics);
        } finally {
            if (ephemeral) {
                deleteRecursively(dir);
            }
        }
    }

    private static String prop(final String name, final String fallback) {
        final String v = System.getProperty("halo.bench." + name);
        return v == null || v.isBlank() ? fallback : v;
    }

    // COMPACTION needs rolled, immutable files to reclaim; size files so the dataset
    // spans several of them even at the small tiers. Other workloads use 1 GiB files
    // to match the original HaloDB preset.
    private static int maxFileSize(final Workload workload, final long records, final int valueSize) {
        if (workload != Workload.COMPACTION) {
            return 1 << 30;
        }
        final long logical = records * (DataGenerator.KEY_SIZE + (long) valueSize);
        return (int) Math.max(4L << 20, Math.min(1L << 30, logical / 16));
    }

    private static void deleteRecursively(final Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }
}
