/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.agrona.concurrent.UnsafeBuffer;

class DbMetaDataFlyweightTest {

    private static UnsafeBuffer direct(final int size) {
        return new UnsafeBuffer(ByteBuffer.allocateDirect(size));
    }

    @Property
    void roundTrip(
            @ForAll boolean open,
            @ForAll boolean ioError,
            @ForAll @IntRange(min = 0, max = 255) int version,
            @ForAll @LongRange(min = 0) long sequenceNumber,
            @ForAll @IntRange(min = 1) int maxFileSize) {

        final UnsafeBuffer buffer = direct(DbMetaDataFlyweight.LENGTH);

        new DbMetaDataFlyweight()
                .wrap(buffer, 0)
                .version(version)
                .open(open)
                .sequenceNumber(sequenceNumber)
                .ioError(ioError)
                .maxFileSize(maxFileSize)
                .storeChecksum();

        final DbMetaDataFlyweight dec = new DbMetaDataFlyweight().wrap(buffer, 0);
        assertEquals(version, dec.version());
        assertEquals(open, dec.open());
        assertEquals(sequenceNumber, dec.sequenceNumber());
        assertEquals(ioError, dec.ioError());
        assertEquals(maxFileSize, dec.maxFileSize());
        assertTrue(dec.verifyChecksum());
    }

    @Property
    void corruptionDetected(@ForAll @IntRange(min = DbMetaDataFlyweight.CHECKSUM_OFFSET + 4, max = 18) int idx) {
        final UnsafeBuffer buffer = direct(DbMetaDataFlyweight.LENGTH);

        new DbMetaDataFlyweight()
                .wrap(buffer, 0)
                .version(1)
                .open(true)
                .sequenceNumber(123L)
                .ioError(false)
                .maxFileSize(1024)
                .storeChecksum();

        buffer.putByte(idx, (byte) (buffer.getByte(idx) ^ 0xFF));

        assertFalse(new DbMetaDataFlyweight().wrap(buffer, 0).verifyChecksum());
    }
}
