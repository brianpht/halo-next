/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import org.agrona.DirectBuffer;
import org.agrona.checksum.Crc32c;

/**
 * CRC32C checksum helper over off-heap DirectBuffer regions, backed by Agrona's
 * hardware-accelerated {@link Crc32c}.
 *
 * <p>The buffer MUST be backed by native (direct / off-heap) memory: the
 * computation reads through the buffer's native {@code addressOffset()}. On-heap
 * byte[] buffers are not supported.
 */
public final class Checksums {

    private Checksums() {}

    /**
     * Computes the CRC32C of {@code length} bytes starting at {@code offset} within
     * the given direct buffer.
     *
     * @param buffer direct (off-heap) buffer.
     * @param offset start offset within the buffer.
     * @param length number of bytes to checksum.
     * @return the CRC32C value as an unsigned 32-bit result widened to long.
     */
    public static long crc32c(final DirectBuffer buffer, final int offset, final int length) {
        return Crc32c.INSTANCE.compute(buffer.addressOffset(), offset, length) & 0xFFFF_FFFFL;
    }
}
