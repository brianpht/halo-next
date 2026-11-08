/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.zip.CRC32C;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.agrona.concurrent.UnsafeBuffer;

class ChecksumsTest {

    @Property
    void matchesJdkCrc32c(@ForAll @IntRange(min = 1, max = 2048) int length, @ForAll long seed) {
        final byte[] data = new byte[length];
        new Random(seed).nextBytes(data);

        final int offset = 7;
        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(offset + length));
        buffer.putBytes(offset, data);

        final CRC32C jdk = new CRC32C();
        jdk.update(data, 0, length);

        assertEquals(jdk.getValue(), Checksums.crc32c(buffer, offset, length));
    }
}
