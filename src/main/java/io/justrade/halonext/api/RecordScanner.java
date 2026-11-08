/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.api;

import io.justrade.halonext.core.HaloEngine;
import io.justrade.halonext.file.DataFile;
import java.io.Closeable;
import java.io.IOException;

/**
 * Scans the live records by walking the immutable data files and keeping only the
 * version the index still points at. Compaction is paused for the scan lifetime so
 * files are not retired underneath the cursor.
 */
final class RecordScanner implements Closeable {

    private final HaloEngine engine;
    private final int[] fileIds;
    private int fileIndex;
    private DataFile.RecordIterator current;
    private int currentFileId;
    private boolean paused;
    private byte[] key;
    private byte[] value;

    RecordScanner(final HaloEngine engine) {
        this.engine = engine;
        engine.pauseCompaction();
        this.paused = true;
        this.fileIds = engine.snapshotFileIds();
    }

    boolean advance(final boolean wantValue) throws IOException {
        while (true) {
            if (current == null) {
                if (fileIndex >= fileIds.length) {
                    close();
                    return false;
                }
                final DataFile file = engine.dataFileForRead(fileIds[fileIndex++]);
                if (file == null) {
                    continue;
                }
                current = file.iterator();
                currentFileId = file.fileId();
            }
            if (current.next()) {
                final byte[] candidate = new byte[current.keySize()];
                current.copyKey(candidate);
                if (engine.isLiveVersion(candidate, currentFileId, current.sequenceNumber())) {
                    key = candidate;
                    if (wantValue) {
                        final byte[] candidateValue = new byte[current.valueSize()];
                        current.copyValue(candidateValue);
                        value = candidateValue;
                    }
                    return true;
                }
            } else {
                current = null;
            }
        }
    }

    byte[] key() {
        return key;
    }

    byte[] value() {
        return value;
    }

    @Override
    public void close() {
        if (paused) {
            engine.resumeCompaction();
            paused = false;
        }
    }
}
