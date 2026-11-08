/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.config;

/**
 * Tuning knobs for the engine. Fluent setters return {@code this}. Defaults favor
 * correctness and durability; tune per deployment (see /docs/decisions).
 */
public final class HaloDBOptions {

    private int maxFileSize = 1 << 30;
    private int maxTombstoneFileSize = 64 * 1024 * 1024;
    private long flushDataSizeBytes = -1;
    private boolean syncWrite;
    private long numberOfRecords = 1_000_000;
    private double compactionThresholdPerFile = 0.75;
    private long compactionJobRate = 1 << 30;
    private boolean compactionEnabled = true;
    private boolean cleanUpInMemoryIndexOnClose = true;

    public int getMaxFileSize() {
        return maxFileSize;
    }

    public HaloDBOptions setMaxFileSize(final int maxFileSize) {
        this.maxFileSize = maxFileSize;
        return this;
    }

    public int getMaxTombstoneFileSize() {
        return maxTombstoneFileSize;
    }

    public HaloDBOptions setMaxTombstoneFileSize(final int maxTombstoneFileSize) {
        this.maxTombstoneFileSize = maxTombstoneFileSize;
        return this;
    }

    public long getFlushDataSizeBytes() {
        return flushDataSizeBytes;
    }

    public HaloDBOptions setFlushDataSizeBytes(final long flushDataSizeBytes) {
        this.flushDataSizeBytes = flushDataSizeBytes;
        return this;
    }

    public boolean isSyncWrite() {
        return syncWrite;
    }

    public HaloDBOptions setSyncWrite(final boolean syncWrite) {
        this.syncWrite = syncWrite;
        return this;
    }

    public long getNumberOfRecords() {
        return numberOfRecords;
    }

    public HaloDBOptions setNumberOfRecords(final long numberOfRecords) {
        this.numberOfRecords = numberOfRecords;
        return this;
    }

    public double getCompactionThresholdPerFile() {
        return compactionThresholdPerFile;
    }

    public HaloDBOptions setCompactionThresholdPerFile(final double compactionThresholdPerFile) {
        this.compactionThresholdPerFile = compactionThresholdPerFile;
        return this;
    }

    public long getCompactionJobRate() {
        return compactionJobRate;
    }

    public HaloDBOptions setCompactionJobRate(final long compactionJobRate) {
        this.compactionJobRate = compactionJobRate;
        return this;
    }

    public boolean isCompactionEnabled() {
        return compactionEnabled;
    }

    public HaloDBOptions setCompactionEnabled(final boolean compactionEnabled) {
        this.compactionEnabled = compactionEnabled;
        return this;
    }

    public boolean isCleanUpInMemoryIndexOnClose() {
        return cleanUpInMemoryIndexOnClose;
    }

    public HaloDBOptions setCleanUpInMemoryIndexOnClose(final boolean cleanUpInMemoryIndexOnClose) {
        this.cleanUpInMemoryIndexOnClose = cleanUpInMemoryIndexOnClose;
        return this;
    }
}
