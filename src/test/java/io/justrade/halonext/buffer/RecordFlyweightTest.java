/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.agrona.concurrent.UnsafeBuffer;

class RecordFlyweightTest {

    private static UnsafeBuffer direct(final int size) {
        return new UnsafeBuffer(ByteBuffer.allocateDirect(size));
    }

    @Property
    void roundTrip(
            @ForAll @Size(min = 1, max = 255) byte[] key,
            @ForAll @Size(min = 1, max = 4096) byte[] value,
            @ForAll @IntRange(min = 0, max = 255) int version,
            @ForAll @LongRange(min = 0) long sequenceNumber) {

        final int total = RecordFlyweight.HEADER_LENGTH + key.length + value.length;
        final UnsafeBuffer buffer = direct(total);

        final RecordFlyweight enc = new RecordFlyweight().wrap(buffer, 0);
        enc.version(version).keySize(key.length).valueSize(value.length).sequenceNumber(sequenceNumber);
        buffer.putBytes(enc.keyOffset(), key);
        buffer.putBytes(enc.valueOffset(), value);
        enc.storeChecksum();

        final RecordFlyweight dec = new RecordFlyweight().wrap(buffer, 0);
        assertEquals(version, dec.version());
        assertEquals(key.length, dec.keySize());
        assertEquals(value.length, dec.valueSize());
        assertEquals(sequenceNumber, dec.sequenceNumber());
        assertEquals(total, dec.recordLength());
        assertTrue(dec.verifyChecksum());

        final byte[] readKey = new byte[key.length];
        buffer.getBytes(dec.keyOffset(), readKey);
        assertArrayEquals(key, readKey);

        final byte[] readValue = new byte[value.length];
        buffer.getBytes(dec.valueOffset(), readValue);
        assertArrayEquals(value, readValue);
    }

    @Property
    void nonZeroOffsetRoundTrip(
            @ForAll @Size(min = 1, max = 64) byte[] key,
            @ForAll @Size(min = 1, max = 256) byte[] value,
            @ForAll @IntRange(min = 0, max = 97) int base) {

        final int total = base + RecordFlyweight.HEADER_LENGTH + key.length + value.length;
        final UnsafeBuffer buffer = direct(total);

        final RecordFlyweight enc = new RecordFlyweight().wrap(buffer, base);
        enc.version(1).keySize(key.length).valueSize(value.length).sequenceNumber(42L);
        buffer.putBytes(enc.keyOffset(), key);
        buffer.putBytes(enc.valueOffset(), value);
        enc.storeChecksum();

        assertTrue(new RecordFlyweight().wrap(buffer, base).verifyChecksum());
    }

    @Property
    void corruptionDetected(
            @ForAll @Size(min = 1, max = 64) byte[] key,
            @ForAll @Size(min = 1, max = 256) byte[] value,
            @ForAll @IntRange(min = 0, max = 10_000) int pick) {

        final int total = RecordFlyweight.HEADER_LENGTH + key.length + value.length;
        final UnsafeBuffer buffer = direct(total);

        final RecordFlyweight enc = new RecordFlyweight().wrap(buffer, 0);
        enc.version(1).keySize(key.length).valueSize(value.length).sequenceNumber(7L);
        buffer.putBytes(enc.keyOffset(), key);
        buffer.putBytes(enc.valueOffset(), value);
        enc.storeChecksum();

        // Corrupt a byte inside the value region (does not alter header-derived lengths).
        final int idx = enc.valueOffset() + (pick % value.length);
        buffer.putByte(idx, (byte) (buffer.getByte(idx) ^ 0xFF));

        assertFalse(new RecordFlyweight().wrap(buffer, 0).verifyChecksum());
    }
}
