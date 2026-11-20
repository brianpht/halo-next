/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.benchmark;

/**
 * Scaled dataset tiers. All tiers use 8-byte keys and a fixed value size, matching
 * the original HaloDB benchmark data model (500M x 1KB at the release tier). Smaller
 * tiers let the suite run on a laptop or a WSL dev box where the release-scale,
 * IO-bound run is not meaningful.
 */
public enum DatasetTier {
    SMOKE(100_000L, 1024),
    SMALL(5_000_000L, 1024),
    MEDIUM(50_000_000L, 1024),
    RELEASE(500_000_000L, 1024);

    private final long records;
    private final int valueSize;

    DatasetTier(final long records, final int valueSize) {
        this.records = records;
        this.valueSize = valueSize;
    }

    public long records() {
        return records;
    }

    public int valueSize() {
        return valueSize;
    }

    public static DatasetTier parse(final String name) {
        return DatasetTier.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
