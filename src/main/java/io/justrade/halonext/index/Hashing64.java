/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.index;

/**
 * 64-bit hash of a byte-array key, the low 64 bits of MurmurHash3 x64 128. Used to
 * select the index segment (high bits) and the bucket within a segment (low bits).
 */
final class Hashing64 {

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private Hashing64() {}

    static long hash(final byte[] data) {
        final int len = data.length;
        final int nblocks = len >> 4;
        long h1 = 0;
        long h2 = 0;

        for (int i = 0; i < nblocks; i++) {
            final int base = i << 4;
            long k1 = getLongLe(data, base);
            long k2 = getLongLe(data, base + 8);

            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        long k1 = 0;
        long k2 = 0;
        final int tail = nblocks << 4;
        final int rem = len & 15;
        for (int i = 0; i < rem; i++) {
            final long b = data[tail + i] & 0xffL;
            if (i < 8) {
                k1 |= b << (8 * i);
            } else {
                k2 |= b << (8 * (i - 8));
            }
        }
        if (rem > 8) {
            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;
        }
        if (rem > 0) {
            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;
        }

        h1 ^= len;
        h2 ^= len;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        return h1;
    }

    private static long getLongLe(final byte[] data, final int offset) {
        return (data[offset] & 0xffL)
                | (data[offset + 1] & 0xffL) << 8
                | (data[offset + 2] & 0xffL) << 16
                | (data[offset + 3] & 0xffL) << 24
                | (data[offset + 4] & 0xffL) << 32
                | (data[offset + 5] & 0xffL) << 40
                | (data[offset + 6] & 0xffL) << 48
                | (data[offset + 7] & 0xffL) << 56;
    }

    private static long fmix64(final long input) {
        long k = input;
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
