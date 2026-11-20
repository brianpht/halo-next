/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.benchmark;

import java.util.Random;

/**
 * Preseeded value generator. A fixed random buffer is filled once; callers reuse a
 * single value array (the engine copies bytes on append), so the driver itself stays
 * near allocation-free on the write path, mirroring the HaloDB RandomDataGenerator.
 */
public final class DataGenerator {

    public static final int KEY_SIZE = 8;

    private final byte[] seed;

    public DataGenerator(final int seedSize, final long rngSeed) {
        this.seed = new byte[Math.max(seedSize, 1)];
        new Random(rngSeed).nextBytes(seed);
    }

    /** Fills {@code dst} with seeded pseudo-random bytes, cycling the seed buffer. */
    public void fillValue(final byte[] dst) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] = seed[i % seed.length];
        }
    }

    /** Writes {@code value} big-endian into the first 8 bytes of {@code dst}. */
    public static void keyFor(final long value, final byte[] dst) {
        long v = value;
        for (int i = KEY_SIZE - 1; i >= 0; i--) {
            dst[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
    }
}
