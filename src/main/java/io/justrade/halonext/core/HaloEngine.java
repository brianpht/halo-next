/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.core;

import io.justrade.halonext.buffer.RecordFlyweight;
import io.justrade.halonext.buffer.TombstoneFlyweight;
import io.justrade.halonext.config.HaloDBOptions;
import io.justrade.halonext.file.DataFile;
import io.justrade.halonext.file.DbDirectory;
import io.justrade.halonext.file.IndexFile;
import io.justrade.halonext.file.TombstoneFile;
import io.justrade.halonext.index.OffHeapIndex;
import io.justrade.halonext.index.RecordLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import org.agrona.collections.Int2IntHashMap;

/**
 * Single-writer, multi-reader storage engine. Writes are serialized by one lock
 * and appended sequentially; reads resolve the latest version through the off-heap
 * index and perform a single disk read (read amplification = 1).
 *
 * <p>This is the internal engine. The public facade lives in the api package.
 */
public final class HaloEngine implements AutoCloseable {

    private static final int HEADER = RecordFlyweight.HEADER_LENGTH;
    private static final int TOMBSTONE_HEADER = TombstoneFlyweight.HEADER_LENGTH;

    private final DbDirectory directory;
    private final HaloDBOptions options;
    private final OffHeapIndex index;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final RecordLocation writerScratch = new RecordLocation();
    private final Int2IntHashMap staleBytesPerFile = new Int2IntHashMap(0);

    private volatile DataFile[] readFiles;

    private DataFile currentWriteFile;
    private IndexFile currentIndexFile;
    private TombstoneFile currentTombstoneFile;

    private long nextSequenceNumber;
    private int nextFileId;
    private boolean closed;

    private HaloEngine(final DbDirectory directory, final HaloDBOptions options, final OffHeapIndex index) {
        this.directory = directory;
        this.options = options;
        this.index = index;
    }

    public static HaloEngine open(final Path path, final HaloDBOptions options) throws IOException {
        final DbDirectory directory = DbDirectory.open(path);
        final OffHeapIndex index = new OffHeapIndex(options.getNumberOfRecords());
        final HaloEngine engine = new HaloEngine(directory, options, index);
        engine.recover();
        return engine;
    }

    public boolean put(final byte[] key, final byte[] value) throws IOException {
        requireValidKey(key);
        writeLock.lock();
        try {
            ensureOpen();
            final long sequenceNumber = nextSequenceNumber++;
            final int recordLength = HEADER + key.length + value.length;
            if (!currentWriteFile.hasCapacityFor(recordLength)) {
                rollWriteFile();
            }
            final int recordOffset = currentWriteFile.appendRecord(key, value, sequenceNumber);
            currentIndexFile.write(key, recordLength, recordOffset, sequenceNumber);
            final int valueOffset = recordOffset + HEADER + key.length;

            if (index.get(key, writerScratch)) {
                markStale(writerScratch.fileId(), HEADER + key.length + writerScratch.valueSize());
            }
            index.put(key, currentWriteFile.fileId(), valueOffset, value.length, sequenceNumber);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    public byte[] get(final byte[] key) throws IOException {
        final RecordLocation location = new RecordLocation();
        if (!index.get(key, location)) {
            return null;
        }
        final DataFile[] files = readFiles;
        final DataFile file = files[location.fileId()];
        return file.readValue(location.valueOffset(), location.valueSize());
    }

    public boolean delete(final byte[] key) throws IOException {
        requireValidKey(key);
        writeLock.lock();
        try {
            ensureOpen();
            if (!index.get(key, writerScratch)) {
                return false;
            }
            final long sequenceNumber = nextSequenceNumber++;
            final int entryLength = TOMBSTONE_HEADER + key.length;
            if (currentTombstoneFile == null || !currentTombstoneFile.hasCapacityFor(entryLength)) {
                rollTombstoneFile();
            }
            currentTombstoneFile.write(key, sequenceNumber);
            markStale(writerScratch.fileId(), HEADER + key.length + writerScratch.valueSize());
            index.remove(key);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    public long size() {
        return index.size();
    }

    @Override
    public void close() throws IOException {
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            currentIndexFile.close();
            if (currentTombstoneFile != null) {
                currentTombstoneFile.close();
            }
            final DataFile[] files = readFiles;
            for (final DataFile file : files) {
                if (file != null) {
                    file.close();
                }
            }
            MetaFile.write(directory.metaFile(), false, false, nextSequenceNumber, options.getMaxFileSize());
            directory.sync();
            if (options.isCleanUpInMemoryIndexOnClose()) {
                index.close();
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void recover() throws IOException {
        final RecordLocation scratch = new RecordLocation();
        long maxSequenceNumber = 0;
        int maxDataFileId = -1;

        final int[] indexFileIds = directory.listIndexFileIds();
        for (final int fileId : indexFileIds) {
            maxDataFileId = Math.max(maxDataFileId, fileId);
            try (IndexFile indexFile = IndexFile.openForRead(directory.path(), fileId)) {
                final IndexFile.IndexFileIterator it = indexFile.iterator();
                while (it.next()) {
                    final int keySize = it.keySize();
                    final byte[] key = new byte[keySize];
                    it.copyKey(key);
                    final int recordSize = it.recordSize();
                    final long sequenceNumber = it.sequenceNumber();
                    final int valueSize = recordSize - HEADER - keySize;
                    final int valueOffset = it.recordOffset() + HEADER + keySize;
                    maxSequenceNumber = Math.max(maxSequenceNumber, sequenceNumber);

                    if (index.get(key, scratch)) {
                        if (scratch.sequenceNumber() < sequenceNumber) {
                            markStale(scratch.fileId(), HEADER + keySize + scratch.valueSize());
                            index.put(key, fileId, valueOffset, valueSize, sequenceNumber);
                        } else {
                            markStale(fileId, recordSize);
                        }
                    } else {
                        index.put(key, fileId, valueOffset, valueSize, sequenceNumber);
                    }
                }
            }
        }

        final int[] tombstoneFileIds = directory.listTombstoneFileIds();
        for (final int fileId : tombstoneFileIds) {
            try (TombstoneFile tombstoneFile = TombstoneFile.openForRead(directory.path(), fileId)) {
                final TombstoneFile.TombstoneFileIterator it = tombstoneFile.iterator();
                while (it.next()) {
                    final int keySize = it.keySize();
                    final byte[] key = new byte[keySize];
                    it.copyKey(key);
                    final long sequenceNumber = it.sequenceNumber();
                    maxSequenceNumber = Math.max(maxSequenceNumber, sequenceNumber);
                    if (index.get(key, scratch) && scratch.sequenceNumber() < sequenceNumber) {
                        markStale(scratch.fileId(), HEADER + keySize + scratch.valueSize());
                        index.remove(key);
                    }
                }
            }
        }

        nextSequenceNumber = maxSequenceNumber + 1;
        nextFileId = maxDataFileId + 1;

        final int[] dataFileIds = directory.listDataFileIds();
        int maxId = maxDataFileId;
        for (final int id : dataFileIds) {
            maxId = Math.max(maxId, id);
        }
        readFiles = new DataFile[maxId + 2];
        for (final int id : dataFileIds) {
            readFiles[id] = DataFile.openForRead(directory.path(), id);
        }

        openNewWriteFile();
        MetaFile.write(directory.metaFile(), true, false, nextSequenceNumber, options.getMaxFileSize());
    }

    private void openNewWriteFile() throws IOException {
        final int fileId = nextFileId++;
        currentWriteFile = DataFile.create(
                directory.path(),
                fileId,
                options.getMaxFileSize(),
                options.getFlushDataSizeBytes(),
                options.isSyncWrite());
        currentIndexFile =
                IndexFile.create(directory.path(), fileId, options.getFlushDataSizeBytes(), options.isSyncWrite());
        registerReadFile(fileId, currentWriteFile);
    }

    private void rollWriteFile() throws IOException {
        currentWriteFile.flush();
        currentIndexFile.close();
        openNewWriteFile();
    }

    private void rollTombstoneFile() throws IOException {
        if (currentTombstoneFile != null) {
            currentTombstoneFile.close();
        }
        currentTombstoneFile = TombstoneFile.create(
                directory.path(),
                nextFileId++,
                options.getMaxTombstoneFileSize(),
                options.getFlushDataSizeBytes(),
                options.isSyncWrite());
    }

    private void registerReadFile(final int fileId, final DataFile file) {
        DataFile[] files = readFiles;
        if (files == null || fileId >= files.length) {
            final DataFile[] grown = new DataFile[fileId + 2];
            if (files != null) {
                System.arraycopy(files, 0, grown, 0, files.length);
            }
            files = grown;
        }
        files[fileId] = file;
        readFiles = files;
    }

    private void markStale(final int fileId, final int bytes) {
        staleBytesPerFile.put(fileId, staleBytesPerFile.get(fileId) + bytes);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Engine is closed");
        }
    }

    private static void requireValidKey(final byte[] key) {
        if (key.length < 1 || key.length > 255) {
            throw new IllegalArgumentException("Key size must be in [1, 255], was " + key.length);
        }
    }
}
