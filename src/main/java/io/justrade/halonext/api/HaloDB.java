/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.api;

import io.justrade.halonext.config.HaloDBOptions;
import io.justrade.halonext.core.HaloEngine;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Public facade for the halo-next embedded key-value store: a fast, persistent,
 * append-only engine with an off-heap index and background compaction.
 */
public final class HaloDB implements AutoCloseable {

    private final HaloEngine engine;

    private HaloDB(final HaloEngine engine) {
        this.engine = engine;
    }

    public static HaloDB open(final String directory, final HaloDBOptions options) throws HaloDBException {
        return open(Paths.get(directory), options);
    }

    public static HaloDB open(final File directory, final HaloDBOptions options) throws HaloDBException {
        return open(directory.toPath(), options);
    }

    public static HaloDB open(final Path directory, final HaloDBOptions options) throws HaloDBException {
        try {
            return new HaloDB(HaloEngine.open(directory, options));
        } catch (final IOException ex) {
            throw new HaloDBException("Failed to open database at " + directory, ex);
        }
    }

    public byte[] get(final byte[] key) throws HaloDBException {
        try {
            return engine.get(key);
        } catch (final IOException ex) {
            throw new HaloDBException(ex);
        }
    }

    public boolean put(final byte[] key, final byte[] value) throws HaloDBException {
        try {
            return engine.put(key, value);
        } catch (final IOException ex) {
            throw new HaloDBException(ex);
        }
    }

    public void delete(final byte[] key) throws HaloDBException {
        try {
            engine.delete(key);
        } catch (final IOException ex) {
            throw new HaloDBException(ex);
        }
    }

    public long size() {
        return engine.size();
    }

    public HaloDBKeyIterator newKeyIterator() {
        return new HaloDBKeyIterator(engine);
    }

    public HaloDBIterator newIterator() {
        return new HaloDBIterator(engine);
    }

    public HaloDBStats stats() throws HaloDBException {
        try {
            return new HaloDBStats(
                    engine.size(),
                    engine.numberOfDataFiles(),
                    engine.numberOfTombstoneFiles(),
                    engine.totalStaleBytes(),
                    engine.numberOfPuts(),
                    engine.numberOfGets(),
                    engine.numberOfDeletes());
        } catch (final IOException ex) {
            throw new HaloDBException(ex);
        }
    }

    public void resetStats() {
        engine.resetStats();
    }

    public void pauseCompaction() {
        engine.pauseCompaction();
    }

    public void resumeCompaction() {
        engine.resumeCompaction();
    }

    public boolean isCompactionComplete() {
        return engine.isCompactionComplete();
    }

    public boolean snapshot() throws HaloDBException {
        try {
            return engine.snapshot();
        } catch (final IOException ex) {
            throw new HaloDBException(ex);
        }
    }

    public boolean clearSnapshot() throws HaloDBException {
        try {
            return engine.clearSnapshot();
        } catch (final IOException ex) {
            throw new HaloDBException(ex);
        }
    }

    public File getSnapshotDirectory() {
        return engine.getSnapshotDirectory().toFile();
    }

    @Override
    public void close() throws HaloDBException {
        try {
            engine.close();
        } catch (final IOException ex) {
            throw new HaloDBException(ex);
        }
    }
}
