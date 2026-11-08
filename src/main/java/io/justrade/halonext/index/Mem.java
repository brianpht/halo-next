/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.index;

import static org.agrona.BufferUtil.ARRAY_BASE_OFFSET;

import org.agrona.UnsafeApi;

/**
 * Raw off-heap memory access over Agrona {@link UnsafeApi}. All multi-byte values
 * use native byte order; the index stores and compares bytes consistently so the
 * order is internal and never observed on disk.
 */
final class Mem {

    private Mem() {}

    static long getLong(final long address) {
        return UnsafeApi.getLong(address);
    }

    static void putLong(final long address, final long value) {
        UnsafeApi.putLong(address, value);
    }

    static int getInt(final long address) {
        return UnsafeApi.getInt(address);
    }

    static void putInt(final long address, final int value) {
        UnsafeApi.putInt(address, value);
    }

    static byte getByte(final long address) {
        return UnsafeApi.getByte(address);
    }

    static void putByte(final long address, final byte value) {
        UnsafeApi.putByte(address, value);
    }

    /** Copies {@code length} bytes from an on-heap array into off-heap memory. */
    static void copyFromArray(final byte[] src, final int srcPos, final long destAddr, final int length) {
        UnsafeApi.copyMemory(src, ARRAY_BASE_OFFSET + srcPos, null, destAddr, length);
    }

    /**
     * Compares {@code length} bytes of an on-heap key against an off-heap key region.
     * Reads in 8-byte chunks (native order on both sides) with a byte-wise tail.
     */
    static boolean keyEquals(final byte[] key, final int length, final long keyAddr) {
        int i = 0;
        for (; i + 8 <= length; i += 8) {
            if (UnsafeApi.getLong(key, ARRAY_BASE_OFFSET + i) != UnsafeApi.getLong(keyAddr + i)) {
                return false;
            }
        }
        for (; i < length; i++) {
            if (key[i] != UnsafeApi.getByte(keyAddr + i)) {
                return false;
            }
        }
        return true;
    }
}
