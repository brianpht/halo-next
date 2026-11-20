/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.benchmark;

import io.justrade.halonext.api.HaloDB;
import io.justrade.halonext.api.HaloDBException;
import io.justrade.halonext.compaction.RateLimiter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Macro workload driver over the public {@link HaloDB} facade. Measures throughput,
 * tail latency (HdrHistogram), on-disk size, and write amplification. This is a
 * cold-path driver: allocation inside the driver is acceptable; only the engine under
 * test must stay on budget.
 *
 * <p>Read amplification is a structural invariant of the engine (one disk read per
 * {@code get}); it is reported as a constant, not measured from outside the facade.
 */
public final class MacroBenchmark {

    private static final long SEED = 100L;

    private final HaloDB db;
    private final Path dir;
    private final long records;
    private final int valueSize;
    private final int readThreads;
    private final long numberOfReads;
    private final long writeRateBytesPerSec;
    private final DataGenerator gen;
    private final byte[] value;

    public MacroBenchmark(
            final HaloDB db,
            final Path dir,
            final long records,
            final int valueSize,
            final int readThreads,
            final long numberOfReads,
            final long writeRateBytesPerSec,
            final DataGenerator gen) {
        this.db = db;
        this.dir = dir;
        this.records = records;
        this.valueSize = valueSize;
        this.readThreads = readThreads;
        this.numberOfReads = numberOfReads;
        this.writeRateBytesPerSec = writeRateBytesPerSec;
        this.gen = gen;
        this.value = new byte[valueSize];
        gen.fillValue(value);
    }

    public Map<String, Object> run(final Workload workload, final long durationSeconds) throws Exception {
        return switch (workload) {
            case FILL_SEQUENCE -> fill(true);
            case FILL_RANDOM -> fill(false);
            case READ_RANDOM -> readRandom();
            case RANDOM_UPDATE -> randomUpdate();
            case READ_AND_UPDATE -> readAndUpdate();
            case COMPACTION -> compaction();
            case SOAK -> soak(durationSeconds);
        };
    }

    private Map<String, Object> fill(final boolean sequential) throws HaloDBException, IOException {
        final byte[] key = new byte[DataGenerator.KEY_SIZE];
        final Random random = new Random(SEED);
        final long start = System.nanoTime();
        for (long i = 0; i < records; i++) {
            final long id = sequential ? i : random.nextInt((int) records);
            DataGenerator.keyFor(id, key);
            db.put(key, value);
        }
        final double elapsed = seconds(start);
        final Map<String, Object> m = baseMetrics(sequential ? "FILL_SEQUENCE" : "FILL_RANDOM");
        m.put("elapsed_s", round(elapsed, 3));
        m.put("throughput_ops_s", round(records / elapsed, 0));
        m.put("write_mb_s", round(records * (double) valueSize / (1024 * 1024) / elapsed, 1));
        addSizeAndAmp(m);
        return m;
    }

    private Map<String, Object> readRandom() throws Exception {
        primeIfNeeded();
        final LatencyStats agg = new LatencyStats();
        final LongAdder misses = new LongAdder();
        final long perThread = Math.max(1, numberOfReads / readThreads);
        final Thread[] threads = new Thread[readThreads];
        final long start = System.nanoTime();
        for (int t = 0; t < readThreads; t++) {
            final int tid = t;
            threads[t] = new Thread(() -> runReader(tid, perThread, agg, misses));
            threads[t].start();
        }
        for (final Thread thread : threads) {
            thread.join();
        }
        final double elapsed = seconds(start);
        final long totalReads = perThread * readThreads;
        final Map<String, Object> m = baseMetrics("READ_RANDOM");
        m.put("read_threads", readThreads);
        m.put("reads", totalReads);
        m.put("misses", misses.sum());
        m.put("elapsed_s", round(elapsed, 3));
        m.put("throughput_ops_s", round(totalReads / elapsed, 0));
        m.put("read_amplification", 1);
        agg.report(m);
        return m;
    }

    private void runReader(final int tid, final long count, final LatencyStats agg, final LongAdder misses) {
        final LatencyStats local = new LatencyStats();
        final Random random = new Random(SEED + tid);
        final byte[] key = new byte[DataGenerator.KEY_SIZE];
        long localMisses = 0;
        for (long i = 0; i < count; i++) {
            DataGenerator.keyFor(random.nextInt((int) records), key);
            final long t0 = System.nanoTime();
            final byte[] v = get(key);
            local.record(System.nanoTime() - t0);
            if (v == null) {
                localMisses++;
            }
        }
        agg.add(local);
        misses.add(localMisses);
    }

    private Map<String, Object> randomUpdate() throws HaloDBException, IOException {
        primeIfNeeded();
        final byte[] key = new byte[DataGenerator.KEY_SIZE];
        final Random random = new Random(SEED);
        final RateLimiter limiter = new RateLimiter(writeRateBytesPerSec);
        final long start = System.nanoTime();
        for (long i = 0; i < records; i++) {
            DataGenerator.keyFor(random.nextInt((int) records), key);
            limiter.acquire(valueSize);
            db.put(key, value);
        }
        final double elapsed = seconds(start);
        final Map<String, Object> m = baseMetrics("RANDOM_UPDATE");
        m.put("updates", records);
        m.put("elapsed_s", round(elapsed, 3));
        m.put("throughput_ops_s", round(records / elapsed, 0));
        addSizeAndAmp(m);
        return m;
    }

    private Map<String, Object> readAndUpdate() throws Exception {
        primeIfNeeded();
        final LatencyStats agg = new LatencyStats();
        final LongAdder misses = new LongAdder();
        final long perThread = Math.max(1, numberOfReads / readThreads);
        final AtomicLong writes = new AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean readersDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        final Thread writer = new Thread(() -> runWriter(readersDone, writes));
        final Thread[] readers = new Thread[readThreads];
        final long start = System.nanoTime();
        writer.start();
        for (int t = 0; t < readThreads; t++) {
            final int tid = t;
            readers[t] = new Thread(() -> runReader(tid, perThread, agg, misses));
            readers[t].start();
        }
        for (final Thread reader : readers) {
            reader.join();
        }
        readersDone.set(true);
        writer.join();
        final double elapsed = seconds(start);
        final long totalReads = perThread * readThreads;
        final Map<String, Object> m = baseMetrics("READ_AND_UPDATE");
        m.put("read_threads", readThreads);
        m.put("reads", totalReads);
        m.put("misses", misses.sum());
        m.put("writes", writes.get());
        m.put("elapsed_s", round(elapsed, 3));
        m.put("read_throughput_ops_s", round(totalReads / elapsed, 0));
        m.put("write_throughput_ops_s", round(writes.get() / elapsed, 0));
        m.put("read_amplification", 1);
        agg.report(m);
        addSizeAndAmp(m);
        return m;
    }

    private Map<String, Object> compaction() throws HaloDBException, IOException {
        final byte[] key = new byte[DataGenerator.KEY_SIZE];
        primeIfNeeded();
        // Accumulate stale data with compaction paused, then time a full reclaim pass.
        db.pauseCompaction();
        for (long i = 0; i < records; i++) {
            DataGenerator.keyFor(i, key);
            db.put(key, value);
        }
        final long staleBefore = db.stats().getTotalStaleBytes();
        final long physicalBefore = physicalBytes(dir);
        final long t0 = System.nanoTime();
        db.resumeCompaction();
        while (!db.isCompactionComplete()) {
            sleepQuietly(5);
        }
        final double elapsed = seconds(t0);
        final long staleAfter = db.stats().getTotalStaleBytes();
        final long physicalAfter = physicalBytes(dir);
        final long reclaimed = Math.max(0, staleBefore - staleAfter);
        final Map<String, Object> m = baseMetrics("COMPACTION");
        m.put("stale_before_bytes", staleBefore);
        m.put("stale_after_bytes", staleAfter);
        m.put("reclaimed_bytes", reclaimed);
        m.put("elapsed_s", round(elapsed, 3));
        m.put("compaction_mb_s", round(reclaimed / (double) (1024 * 1024) / Math.max(elapsed, 1e-9), 1));
        m.put("physical_before_bytes", physicalBefore);
        m.put("physical_after_bytes", physicalAfter);
        return m;
    }

    private Map<String, Object> soak(final long durationSeconds) throws Exception {
        primeIfNeeded();
        final LatencyStats agg = new LatencyStats();
        final LongAdder misses = new LongAdder();
        final AtomicLong reads = new AtomicLong();
        final AtomicLong writes = new AtomicLong();
        final long deadline = System.nanoTime() + durationSeconds * 1_000_000_000L;

        final Thread writer = new Thread(() -> {
            final byte[] key = new byte[DataGenerator.KEY_SIZE];
            final Random random = new Random(SEED + 2_000);
            long count = 0;
            while (System.nanoTime() < deadline) {
                DataGenerator.keyFor(random.nextInt((int) records), key);
                put(key);
                count++;
            }
            writes.set(count);
        });
        final Thread[] readers = new Thread[readThreads];
        final long start = System.nanoTime();
        writer.start();
        for (int t = 0; t < readThreads; t++) {
            final int tid = t;
            readers[t] = new Thread(() -> {
                final LatencyStats local = new LatencyStats();
                final Random random = new Random(SEED + tid);
                final byte[] key = new byte[DataGenerator.KEY_SIZE];
                long localMisses = 0;
                long localReads = 0;
                while (System.nanoTime() < deadline) {
                    DataGenerator.keyFor(random.nextInt((int) records), key);
                    final long t0 = System.nanoTime();
                    final byte[] v = get(key);
                    local.record(System.nanoTime() - t0);
                    localReads++;
                    if (v == null) {
                        localMisses++;
                    }
                }
                agg.add(local);
                misses.add(localMisses);
                reads.addAndGet(localReads);
            });
            readers[tid].start();
        }
        for (final Thread reader : readers) {
            reader.join();
        }
        writer.join();
        final double elapsed = seconds(start);
        final Map<String, Object> m = baseMetrics("SOAK");
        m.put("duration_s", durationSeconds);
        m.put("read_threads", readThreads);
        m.put("reads", reads.get());
        m.put("writes", writes.get());
        m.put("misses", misses.sum());
        m.put("read_throughput_ops_s", round(reads.get() / elapsed, 0));
        m.put("write_throughput_ops_s", round(writes.get() / elapsed, 0));
        m.put("note", "attach an allocation profiler (-prof gc / async-profiler) to verify zero steady-state GC");
        agg.report(m);
        addSizeAndAmp(m);
        return m;
    }

    private Map<String, Object> baseMetrics(final String workload) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("workload", workload);
        m.put("records", records);
        m.put("key_size", DataGenerator.KEY_SIZE);
        m.put("value_size", valueSize);
        return m;
    }

    /** Fills the dataset with sequential keys once, unless the DB is already populated. */
    private void primeIfNeeded() throws HaloDBException {
        if (db.size() >= records) {
            return;
        }
        final byte[] key = new byte[DataGenerator.KEY_SIZE];
        for (long i = 0; i < records; i++) {
            DataGenerator.keyFor(i, key);
            db.put(key, value);
        }
    }

    private void runWriter(final java.util.concurrent.atomic.AtomicBoolean stop, final AtomicLong writes) {
        final byte[] key = new byte[DataGenerator.KEY_SIZE];
        final Random random = new Random(SEED + 1_000);
        long count = 0;
        while (!stop.get()) {
            DataGenerator.keyFor(random.nextInt((int) records), key);
            put(key);
            count++;
        }
        writes.set(count);
    }

    private void addSizeAndAmp(final Map<String, Object> m) throws IOException {
        final long physical = physicalBytes(dir);
        final long logical = records * (DataGenerator.KEY_SIZE + (long) valueSize);
        m.put("db_physical_bytes", physical);
        m.put("db_logical_bytes", logical);
        m.put("write_amplification", round(physical / (double) Math.max(logical, 1), 2));
    }

    private byte[] get(final byte[] key) {
        try {
            return db.get(key);
        } catch (final HaloDBException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void put(final byte[] key) {
        try {
            db.put(key, value);
        } catch (final HaloDBException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long physicalBytes(final Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (final IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    })
                    .sum();
        }
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static double seconds(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static double round(final double value, final int decimals) {
        final double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
