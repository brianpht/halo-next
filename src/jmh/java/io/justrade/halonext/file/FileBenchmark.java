/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * File-layer benchmarks: sequential buffered {@code append} and warm random
 * {@code readValue}. These are I/O bound; the numbers are indicative only and are
 * deliberately NOT wired into {@code jmhBudgetCheck} because the filesystem (and
 * especially a WSL / overlay filesystem used during development) makes them too
 * noisy to gate on. Run them for relative comparison, not as a hard budget.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class FileBenchmark {

    private static final int READ_RECORDS = 100_000;
    private static final int VALUE_SIZE = 100;
    // 10 MiB buffered flush, mirroring the macro-harness write preset.
    private static final long FLUSH_BYTES = 10L * 1024 * 1024;

    private Path dir;
    private DataFile writeFile;
    private DataFile readFile;
    private byte[] key;
    private byte[] value;
    private int[] valueOffsets;
    private long sequence;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        dir = Files.createTempDirectory("halo-next-jmh-file");
        key = "key-0000000000".getBytes(StandardCharsets.UTF_8);
        value = new byte[VALUE_SIZE];

        // File 0 is the append target; a huge cap keeps a short run from rolling.
        writeFile = DataFile.create(dir, 0, Integer.MAX_VALUE, FLUSH_BYTES, false);

        // File 1 is prepopulated, then reopened read-only for the random-read path.
        final DataFile src = DataFile.create(dir, 1, Integer.MAX_VALUE, FLUSH_BYTES, false);
        valueOffsets = new int[READ_RECORDS];
        final int headerAndKey = io.justrade.halonext.buffer.RecordFlyweight.HEADER_LENGTH + key.length;
        for (int i = 0; i < READ_RECORDS; i++) {
            final int recordOffset = src.appendRecord(key, value, i + 1L);
            valueOffsets[i] = recordOffset + headerAndKey;
        }
        src.flush();
        src.close();
        readFile = DataFile.openForRead(dir, 1);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        writeFile.close();
        readFile.close();
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

    @Benchmark
    public int append() throws IOException {
        return writeFile.appendRecord(key, value, sequence++);
    }

    @Benchmark
    public byte[] readValue() throws IOException {
        final int i = cursor;
        cursor = i + 1 == READ_RECORDS ? 0 : i + 1;
        return readFile.readValue(valueOffsets[i], VALUE_SIZE);
    }
}
