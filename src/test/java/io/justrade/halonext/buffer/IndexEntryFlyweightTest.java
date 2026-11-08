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

class IndexEntryFlyweightTest {

    private static UnsafeBuffer direct(final int size) {
        return new UnsafeBuffer(ByteBuffer.allocateDirect(size));
    }

    @Property
    void roundTrip(
            @ForAll @Size(min = 1, max = 255) byte[] key,
            @ForAll @IntRange(min = 1) int recordSize,
            @ForAll @IntRange(min = 0) int recordOffset,
            @ForAll @IntRange(min = 0, max = 255) int version,
            @ForAll @LongRange(min = 0) long sequenceNumber) {

        final int total = IndexEntryFlyweight.HEADER_LENGTH + key.length;
        final UnsafeBuffer buffer = direct(total);

        final IndexEntryFlyweight enc = new IndexEntryFlyweight().wrap(buffer, 0);
        enc.version(version)
                .keySize(key.length)
                .recordSize(recordSize)
                .recordOffset(recordOffset)
                .sequenceNumber(sequenceNumber);
        buffer.putBytes(enc.keyOffset(), key);
        enc.storeChecksum();

        final IndexEntryFlyweight dec = new IndexEntryFlyweight().wrap(buffer, 0);
        assertEquals(version, dec.version());
        assertEquals(key.length, dec.keySize());
        assertEquals(recordSize, dec.recordSize());
        assertEquals(recordOffset, dec.recordOffset());
        assertEquals(sequenceNumber, dec.sequenceNumber());
        assertEquals(total, dec.entryLength());
        assertTrue(dec.verifyChecksum());

        final byte[] readKey = new byte[key.length];
        buffer.getBytes(dec.keyOffset(), readKey);
        assertArrayEquals(key, readKey);
    }

    @Property
    void corruptionDetected(
            @ForAll @Size(min = 1, max = 255) byte[] key, @ForAll @IntRange(min = 0, max = 10_000) int pick) {

        final int total = IndexEntryFlyweight.HEADER_LENGTH + key.length;
        final UnsafeBuffer buffer = direct(total);

        final IndexEntryFlyweight enc = new IndexEntryFlyweight().wrap(buffer, 0);
        enc.version(1).keySize(key.length).recordSize(100).recordOffset(5).sequenceNumber(7L);
        buffer.putBytes(enc.keyOffset(), key);
        enc.storeChecksum();

        final int idx = enc.keyOffset() + (pick % key.length);
        buffer.putByte(idx, (byte) (buffer.getByte(idx) ^ 0xFF));

        assertFalse(new IndexEntryFlyweight().wrap(buffer, 0).verifyChecksum());
    }
}
