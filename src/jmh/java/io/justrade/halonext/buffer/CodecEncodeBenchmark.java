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
 * Micro-benchmark for the codec write path: wrap a flyweight, write the header
 * fields, and store the CRC32C. The variable-length key and value bytes are
 * pre-written in setup so each benchmark measures the header-plus-checksum cost
 * that mirrors the {@code decodeAndVerify} read counterpart. Budget: record
 * decode / encode (flyweight wrap + read / write) &lt; 100 ns.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CodecEncodeBenchmark {

    private static final int KEY_SIZE = 16;
    private static final int VALUE_SIZE = 100;

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final RecordFlyweight record = new RecordFlyweight();
    private final IndexEntryFlyweight indexEntry = new IndexEntryFlyweight();
    private final TombstoneFlyweight tombstone = new TombstoneFlyweight();

    private long sequence;

    @Setup
    public void setup() {
        final byte[] key = new byte[KEY_SIZE];
        final byte[] value = new byte[VALUE_SIZE];
        // Pre-write the key and value payloads once; the encode benchmarks only
        // rewrite the header and checksum, matching the decode-path micro-benchmark.
        // keySize must be set before valueOffset() can resolve the value position.
        final RecordFlyweight r = record.wrap(buffer, 0).keySize(KEY_SIZE);
        buffer.putBytes(r.keyOffset(), key);
        buffer.putBytes(r.valueOffset(), value);
    }

    @Benchmark
    public long encodeRecord() {
        final long seq = sequence++;
        final RecordFlyweight r = record.wrap(buffer, 0);
        r.version(1).keySize(KEY_SIZE).valueSize(VALUE_SIZE).sequenceNumber(seq).storeChecksum();
        return r.checksum();
    }

    @Benchmark
    public long encodeIndexEntry() {
        final long seq = sequence++;
        final IndexEntryFlyweight e = indexEntry.wrap(buffer, 0);
        e.version(1).keySize(KEY_SIZE).recordSize(134).recordOffset(4096).sequenceNumber(seq);
        e.storeChecksum();
        return e.checksum();
    }

    @Benchmark
    public long encodeTombstone() {
        final long seq = sequence++;
        final TombstoneFlyweight t = tombstone.wrap(buffer, 0);
        t.version(1).sequenceNumber(seq).keySize(KEY_SIZE).storeChecksum();
        return t.checksum();
    }
}
