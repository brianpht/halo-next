/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Reusable direct encode buffer for appending to a file. Grows to the largest
 * record seen and is reused for every subsequent append, so steady-state appends
 * allocate nothing. The backing buffer is direct so CRC32C can be computed over it.
 */
final class AppendBuffer {

    private static final int MIN_CAPACITY = 256;

    private ByteBuffer byteBuffer;
    private UnsafeBuffer buffer;

    /** Returns a flyweight-wrappable buffer with at least {@code required} capacity. */
    UnsafeBuffer buffer(final int required) {
        ensureCapacity(required);
        return buffer;
    }

    /** Returns the backing ByteBuffer positioned at 0 with limit {@code length}, ready to write to a channel. */
    ByteBuffer readyForWrite(final int length) {
        byteBuffer.clear();
        byteBuffer.limit(length);
        return byteBuffer;
    }

    private void ensureCapacity(final int required) {
        if (byteBuffer == null || byteBuffer.capacity() < required) {
            int capacity = Math.max(required, MIN_CAPACITY);
            capacity = Integer.highestOneBit(capacity - 1) << 1;
            byteBuffer = ByteBuffer.allocateDirect(capacity);
            buffer = new UnsafeBuffer(byteBuffer);
        }
    }
}
