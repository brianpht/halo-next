/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.index;

import java.nio.charset.StandardCharsets;
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
 * Micro-benchmark for an off-heap index lookup hit. Budget: index lookup
 * (off-heap, hit) &lt; 200 ns.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class IndexLookupBenchmark {

    private static final int KEY_COUNT = 1_000_000;

    private OffHeapIndex index;
    private byte[][] keys;
    private RecordLocation loc;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() {
        index = new OffHeapIndex(KEY_COUNT);
        keys = new byte[KEY_COUNT][];
        for (int i = 0; i < KEY_COUNT; i++) {
            keys[i] = ("key-" + i).getBytes(StandardCharsets.UTF_8);
            index.put(keys[i], i % 16, i * 32, 32, i + 1L);
        }
        loc = new RecordLocation();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        index.close();
    }

    @Benchmark
    public boolean lookupHit() {
        final int i = cursor;
        cursor = i + 1 == KEY_COUNT ? 0 : i + 1;
        return index.get(keys[i], loc);
    }
}
