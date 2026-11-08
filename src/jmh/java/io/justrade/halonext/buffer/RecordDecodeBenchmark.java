/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Micro-benchmark for record header decode and checksum verification. Budget:
 * record decode (flyweight wrap + read) &lt; 100 ns.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RecordDecodeBenchmark {

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final RecordFlyweight record = new RecordFlyweight();

    @Setup
    public void setup() {
        final byte[] key = new byte[16];
        final byte[] value = new byte[100];
        final RecordFlyweight enc = record.wrap(buffer, 0);
        enc.version(1).keySize(key.length).valueSize(value.length).sequenceNumber(123_456_789L);
        buffer.putBytes(enc.keyOffset(), key);
        buffer.putBytes(enc.valueOffset(), value);
        enc.storeChecksum();
    }

    @Benchmark
    public long decodeHeader() {
        final RecordFlyweight r = record.wrap(buffer, 0);
        return r.version() + r.keySize() + r.valueSize() + r.sequenceNumber() + r.recordLength();
    }

    @Benchmark
    public boolean decodeAndVerify() {
        return record.wrap(buffer, 0).verifyChecksum();
    }
}
