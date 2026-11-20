/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.core;

import io.justrade.halonext.config.HaloDBOptions;
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
 * End-to-end put and get benchmarks. Budgets: put (append + index update,
 * buffered) &lt; 5 us; get (index hit + 1 disk read, warm) &lt; 10 us.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class EngineBenchmark {

    private static final int KEY_COUNT = 100_000;

    private Path dir;
    private HaloEngine engine;
    private byte[][] keys;
    private byte[][] missKeys;
    private byte[] value;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        dir = Files.createTempDirectory("halo-next-jmh");
        engine = HaloEngine.open(
                dir, new HaloDBOptions().setNumberOfRecords(KEY_COUNT).setCompactionEnabled(false));
        keys = new byte[KEY_COUNT][];
        missKeys = new byte[KEY_COUNT][];
        value = new byte[100];
        for (int i = 0; i < KEY_COUNT; i++) {
            keys[i] = ("key-" + i).getBytes(StandardCharsets.UTF_8);
            missKeys[i] = ("absent-" + i).getBytes(StandardCharsets.UTF_8);
            engine.put(keys[i], value);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        engine.close();
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
    public byte[] get() throws IOException {
        final int i = cursor;
        cursor = i + 1 == KEY_COUNT ? 0 : i + 1;
        return engine.get(keys[i]);
    }

    @Benchmark
    public byte[] getMiss() throws IOException {
        final int i = cursor;
        cursor = i + 1 == KEY_COUNT ? 0 : i + 1;
        return engine.get(missKeys[i]);
    }

    @Benchmark
    public boolean put() throws IOException {
        final int i = cursor;
        cursor = i + 1 == KEY_COUNT ? 0 : i + 1;
        return engine.put(keys[i], value);
    }
}
