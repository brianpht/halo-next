/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.api;

/** Immutable snapshot of engine statistics at the moment {@link HaloDB#stats()} is called. */
public final class HaloDBStats {

    private final long size;
    private final int numberOfDataFiles;
    private final int numberOfTombstoneFiles;
    private final long totalStaleBytes;
    private final long numberOfPuts;
    private final long numberOfGets;
    private final long numberOfDeletes;

    HaloDBStats(
            final long size,
            final int numberOfDataFiles,
            final int numberOfTombstoneFiles,
            final long totalStaleBytes,
            final long numberOfPuts,
            final long numberOfGets,
            final long numberOfDeletes) {
        this.size = size;
        this.numberOfDataFiles = numberOfDataFiles;
        this.numberOfTombstoneFiles = numberOfTombstoneFiles;
        this.totalStaleBytes = totalStaleBytes;
        this.numberOfPuts = numberOfPuts;
        this.numberOfGets = numberOfGets;
        this.numberOfDeletes = numberOfDeletes;
    }

    public long getSize() {
        return size;
    }

    public int getNumberOfDataFiles() {
        return numberOfDataFiles;
    }

    public int getNumberOfTombstoneFiles() {
        return numberOfTombstoneFiles;
    }

    public long getTotalStaleBytes() {
        return totalStaleBytes;
    }

    public long getNumberOfPuts() {
        return numberOfPuts;
    }

    public long getNumberOfGets() {
        return numberOfGets;
    }

    public long getNumberOfDeletes() {
        return numberOfDeletes;
    }

    @Override
    public String toString() {
        return "HaloDBStats{size=" + size
                + ", dataFiles=" + numberOfDataFiles
                + ", tombstoneFiles=" + numberOfTombstoneFiles
                + ", totalStaleBytes=" + totalStaleBytes
                + ", puts=" + numberOfPuts
                + ", gets=" + numberOfGets
                + ", deletes=" + numberOfDeletes
                + '}';
    }
}
