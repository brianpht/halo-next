/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.core;

import io.justrade.halonext.buffer.RecordFlyweight;
import io.justrade.halonext.buffer.TombstoneFlyweight;
import io.justrade.halonext.compaction.CompactionAgent;
import io.justrade.halonext.compaction.CompactionTarget;
import io.justrade.halonext.compaction.RateLimiter;
import io.justrade.halonext.config.HaloDBOptions;
import io.justrade.halonext.file.DataFile;
import io.justrade.halonext.file.DbDirectory;
import io.justrade.halonext.file.FileNames;
import io.justrade.halonext.file.IndexFile;
import io.justrade.halonext.file.TombstoneFile;
import io.justrade.halonext.index.OffHeapIndex;
import io.justrade.halonext.index.RecordLocation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * Single-writer, multi-reader storage engine. Writes are serialized by one lock
 * and appended sequentially; reads resolve the latest version through the off-heap
 * index and perform a single disk read (read amplification = 1). A background
 * compaction agent reclaims stale space out of place.
 *
 * <p>This is the internal engine. The public facade lives in the api package.
 */
public final class HaloEngine implements AutoCloseable, CompactionTarget {

    private static final int HEADER = RecordFlyweight.HEADER_LENGTH;
    private static final int TOMBSTONE_HEADER = TombstoneFlyweight.HEADER_LENGTH;
    private static final int COMPACTION_MESSAGE = 1;
    private static final int QUEUE_CAPACITY = 1 << 16;

    private final DbDirectory directory;
    private final HaloDBOptions options;
    private final OffHeapIndex index;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final Object fileMutex = new Object();
    private final RecordLocation writerScratch = new RecordLocation();
    private final Int2IntHashMap staleBytesPerFile = new Int2IntHashMap(0);

    // Compaction work queue (serialized writer-side producers; single consumer: the agent).
    private final ManyToOneRingBuffer compactionQueue = new ManyToOneRingBuffer(
            new UnsafeBuffer(ByteBuffer.allocateDirect(QUEUE_CAPACITY + RingBufferDescriptor.TRAILER_LENGTH)));
    private final UnsafeBuffer enqueueBuffer = new UnsafeBuffer(new byte[Integer.BYTES]);
    private final IntHashSet queuedFiles = new IntHashSet();
    private final MessageHandler pollHandler = this::onPolledFileId;

    private volatile DataFile[] readFiles;
    private volatile boolean compacting;
    private int polledFileId = -1;

    private DataFile currentWriteFile;
    private IndexFile currentIndexFile;
    private TombstoneFile currentTombstoneFile;
    private DataFile compactionWriteFile;
    private IndexFile compactionIndexFile;

    private AgentRunner compactionRunner;

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
        engine.startCompaction();
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
        for (int attempt = 0; attempt < 5; attempt++) {
            if (!index.get(key, location)) {
                return null;
            }
            final DataFile[] files = readFiles;
            final int fileId = location.fileId();
            if (fileId >= files.length || files[fileId] == null) {
                continue; // file is being compacted; re-resolve to its new location
            }
            try {
                return files[fileId].readValue(location.valueOffset(), location.valueSize());
            } catch (final ClosedChannelException retryable) {
                // The file was retired mid-read; re-resolve and retry.
            }
        }
        return null;
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

    public boolean isCompactionComplete() {
        return compactionQueue.size() == 0 && !compacting;
    }

    @Override
    public void close() throws IOException {
        CloseHelper.quietClose(compactionRunner);
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            CloseHelper.quietClose(currentIndexFile);
            CloseHelper.quietClose(compactionIndexFile);
            CloseHelper.quietClose(currentTombstoneFile);
            final DataFile[] files = readFiles;
            for (final DataFile file : files) {
                CloseHelper.quietClose(file);
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

    // --- CompactionTarget ---

    @Override
    public int pollFileId() {
        polledFileId = -1;
        compactionQueue.read(pollHandler, 1);
        if (polledFileId >= 0) {
            writeLock.lock();
            try {
                queuedFiles.remove(polledFileId);
            } finally {
                writeLock.unlock();
            }
        }
        return polledFileId;
    }

    @Override
    public long fileSizeBytes(final int fileId) {
        final DataFile[] files = readFiles;
        if (fileId >= files.length || files[fileId] == null) {
            return 0;
        }
        return files[fileId].writeOffset();
    }

    @Override
    public void compact(final int fileId) throws IOException {
        if (isUncompactable(fileId)) {
            return;
        }
        final DataFile source = readFiles[fileId];
        if (source == null) {
            return;
        }
        compacting = true;
        try {
            final RecordLocation location = new RecordLocation();
            final DataFile.RecordIterator it = source.iterator();
            while (it.next()) {
                final int keySize = it.keySize();
                final byte[] key = new byte[keySize];
                it.copyKey(key);
                final long sequenceNumber = it.sequenceNumber();
                if (index.get(key, location)
                        && location.fileId() == fileId
                        && location.sequenceNumber() == sequenceNumber) {
                    final byte[] value = new byte[it.valueSize()];
                    it.copyValue(value);
                    migrateRecord(key, value, sequenceNumber);
                }
            }
            retireFile(fileId, source);
        } finally {
            compacting = false;
        }
    }

    // --- recovery and file management ---

    private void recover() throws IOException {
        final RecordLocation scratch = new RecordLocation();
        long maxSequenceNumber = 0;
        int maxDataFileId = -1;

        for (final int fileId : directory.listIndexFileIds()) {
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

        for (final int fileId : directory.listTombstoneFileIds()) {
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

    private void startCompaction() {
        if (!options.isCompactionEnabled()) {
            return;
        }
        final RateLimiter rateLimiter = new RateLimiter(options.getCompactionJobRate());
        final CompactionAgent agent = new CompactionAgent(this, rateLimiter);
        final ErrorHandler errorHandler = Throwable::printStackTrace;
        compactionRunner = new AgentRunner(new BackoffIdleStrategy(), errorHandler, null, agent);
        AgentRunner.startOnThread(compactionRunner);
    }

    private void migrateRecord(final byte[] key, final byte[] value, final long sequenceNumber) throws IOException {
        final int recordLength = HEADER + key.length + value.length;
        if (compactionWriteFile == null || !compactionWriteFile.hasCapacityFor(recordLength)) {
            rollCompactionFile();
        }
        final int recordOffset = compactionWriteFile.appendRecord(key, value, sequenceNumber);
        compactionIndexFile.write(key, recordLength, recordOffset, sequenceNumber);
        final int valueOffset = recordOffset + HEADER + key.length;
        index.replace(key, sequenceNumber, compactionWriteFile.fileId(), valueOffset, value.length, sequenceNumber);
    }

    private void retireFile(final int fileId, final DataFile source) throws IOException {
        unregisterReadFile(fileId);
        source.close();
        Files.deleteIfExists(directory.path().resolve(FileNames.dataFile(fileId)));
        Files.deleteIfExists(directory.path().resolve(FileNames.indexFile(fileId)));
        writeLock.lock();
        try {
            staleBytesPerFile.remove(fileId);
        } finally {
            writeLock.unlock();
        }
    }

    private void openNewWriteFile() throws IOException {
        final int fileId = allocateFileId();
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

    private void rollCompactionFile() throws IOException {
        if (compactionIndexFile != null) {
            compactionIndexFile.close();
        }
        final int fileId = allocateFileId();
        compactionWriteFile = DataFile.create(
                directory.path(),
                fileId,
                options.getMaxFileSize(),
                options.getFlushDataSizeBytes(),
                options.isSyncWrite());
        compactionIndexFile =
                IndexFile.create(directory.path(), fileId, options.getFlushDataSizeBytes(), options.isSyncWrite());
        registerReadFile(fileId, compactionWriteFile);
    }

    private void rollTombstoneFile() throws IOException {
        if (currentTombstoneFile != null) {
            currentTombstoneFile.close();
        }
        currentTombstoneFile = TombstoneFile.create(
                directory.path(),
                allocateFileId(),
                options.getMaxTombstoneFileSize(),
                options.getFlushDataSizeBytes(),
                options.isSyncWrite());
    }

    private int allocateFileId() {
        synchronized (fileMutex) {
            return nextFileId++;
        }
    }

    private void registerReadFile(final int fileId, final DataFile file) {
        synchronized (fileMutex) {
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
    }

    private void unregisterReadFile(final int fileId) {
        synchronized (fileMutex) {
            final DataFile[] files = readFiles;
            if (fileId < files.length) {
                final DataFile[] copy = files.clone();
                copy[fileId] = null;
                readFiles = copy;
            }
        }
    }

    private void markStale(final int fileId, final int bytes) {
        staleBytesPerFile.put(fileId, staleBytesPerFile.get(fileId) + bytes);
        maybeEnqueueForCompaction(fileId);
    }

    private void maybeEnqueueForCompaction(final int fileId) {
        if (currentWriteFile == null || fileId == currentWriteFile.fileId()) {
            return;
        }
        final DataFile[] files = readFiles;
        if (files == null || fileId >= files.length || files[fileId] == null) {
            return;
        }
        final int total = files[fileId].writeOffset();
        if (total <= 0) {
            return;
        }
        final double staleRatio = (double) staleBytesPerFile.get(fileId) / total;
        if (staleRatio >= options.getCompactionThresholdPerFile() && queuedFiles.add(fileId)) {
            enqueueBuffer.putInt(0, fileId);
            if (!compactionQueue.write(COMPACTION_MESSAGE, enqueueBuffer, 0, Integer.BYTES)) {
                queuedFiles.remove(fileId);
            }
        }
    }

    private boolean isUncompactable(final int fileId) {
        return (currentWriteFile != null && fileId == currentWriteFile.fileId())
                || (compactionWriteFile != null && fileId == compactionWriteFile.fileId());
    }

    private void onPolledFileId(
            final int msgTypeId, final MutableDirectBuffer buffer, final int offset, final int length) {
        polledFileId = buffer.getInt(offset);
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
