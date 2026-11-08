/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.index;

import java.util.concurrent.atomic.AtomicLong;
import org.agrona.UnsafeApi;

/**
 * Off-heap allocator over Agrona {@link UnsafeApi} with byte accounting. The
 * running total enables close-time leak assertions and index-size statistics.
 */
public final class NativeAllocator {

    private final AtomicLong allocatedBytes = new AtomicLong();

    /** Allocates {@code size} bytes of uninitialized native memory. */
    public long allocate(final long size) {
        final long address = UnsafeApi.allocateMemory(size);
        allocatedBytes.addAndGet(size);
        return address;
    }

    /** Allocates {@code size} bytes of native memory zero-filled. */
    public long allocateZeroed(final long size) {
        final long address = allocate(size);
        UnsafeApi.setMemory(address, size, (byte) 0);
        return address;
    }

    /** Frees a prior allocation of exactly {@code size} bytes. */
    public void free(final long address, final long size) {
        UnsafeApi.freeMemory(address);
        allocatedBytes.addAndGet(-size);
    }

    public long allocatedBytes() {
        return allocatedBytes.get();
    }
}
