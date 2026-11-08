/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import io.justrade.halonext.buffer.Format;
import io.justrade.halonext.buffer.IndexEntryFlyweight;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Append-only index file paired with a data file. Each entry locates a record in
 * the data file; the full index set is replayed at open to rebuild the off-heap
 * index.
 */
public final class IndexFile implements AutoCloseable {

    private final int fileId;
    private final Path path;
    private final FileChannel channel;
    private final long flushDataSizeBytes;
    private final boolean syncWrite;

    private final AppendBuffer appendBuffer = new AppendBuffer();
    private final IndexEntryFlyweight entry = new IndexEntryFlyweight();

    private long unflushedBytes;

    private IndexFile(
            final int fileId,
            final Path path,
            final FileChannel channel,
            final long flushDataSizeBytes,
            final boolean syncWrite) {
        this.fileId = fileId;
        this.path = path;
        this.channel = channel;
        this.flushDataSizeBytes = flushDataSizeBytes;
        this.syncWrite = syncWrite;
    }

    public static IndexFile create(
            final Path dir, final int fileId, final long flushDataSizeBytes, final boolean syncWrite)
            throws IOException {
        final Path file = dir.resolve(FileNames.indexFile(fileId));
        final FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ);
        return new IndexFile(fileId, file, channel, flushDataSizeBytes, syncWrite);
    }

    public static IndexFile openForRead(final Path dir, final int fileId) throws IOException {
        final Path file = dir.resolve(FileNames.indexFile(fileId));
        final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        return new IndexFile(fileId, file, channel, -1, false);
    }

    public int fileId() {
        return fileId;
    }

    public void write(final byte[] key, final int recordSize, final int recordOffset, final long sequenceNumber)
            throws IOException {
        final int entryLength = IndexEntryFlyweight.HEADER_LENGTH + key.length;
        final UnsafeBuffer encode = appendBuffer.buffer(entryLength);
        final IndexEntryFlyweight fly = entry.wrap(encode, 0);
        fly.version(Format.INDEX_FILE_VERSION)
                .keySize(key.length)
                .recordSize(recordSize)
                .recordOffset(recordOffset)
                .sequenceNumber(sequenceNumber);
        encode.putBytes(fly.keyOffset(), key);
        fly.storeChecksum();

        writeFully(appendBuffer.readyForWrite(entryLength));
        unflushedBytes += entryLength;
        if (syncWrite || (flushDataSizeBytes >= 0 && unflushedBytes > flushDataSizeBytes)) {
            flush();
        }
    }

    /** Opens a replay iterator over all valid entries; stops at the first corrupt or torn entry. */
    public IndexFileIterator iterator() throws IOException {
        return new IndexFileIterator(FileChannels.readAll(channel));
    }

    public void flush() throws IOException {
        channel.force(true);
        unflushedBytes = 0;
    }

    @Override
    public void close() throws IOException {
        if (channel.isOpen()) {
            if (unflushedBytes > 0) {
                flush();
            }
            channel.close();
        }
    }

    private void writeFully(final ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** Forward cursor over index entries. Reuses one flyweight; allocation-free per step. */
    public static final class IndexFileIterator {

        private final UnsafeBuffer buffer;
        private final int limit;
        private final IndexEntryFlyweight entry = new IndexEntryFlyweight();
        private int position;

        IndexFileIterator(final UnsafeBuffer buffer) {
            this.buffer = buffer;
            this.limit = buffer.capacity();
        }

        /**
         * Advances to the next valid entry and returns true, leaving the accessors
         * pointed at it. Returns false at end, on a torn tail, or on corruption.
         */
        public boolean next() {
            if (position + IndexEntryFlyweight.HEADER_LENGTH > limit) {
                return false;
            }
            final IndexEntryFlyweight fly = entry.wrap(buffer, position);
            final int entryLength = IndexEntryFlyweight.HEADER_LENGTH + fly.keySize();
            if (position + entryLength > limit || !fly.verifyChecksum()) {
                return false;
            }
            position += entryLength;
            return true;
        }

        public int version() {
            return entry.version();
        }

        public int keySize() {
            return entry.keySize();
        }

        public int recordSize() {
            return entry.recordSize();
        }

        public int recordOffset() {
            return entry.recordOffset();
        }

        public long sequenceNumber() {
            return entry.sequenceNumber();
        }

        public void copyKey(final byte[] dst) {
            buffer.getBytes(entry.keyOffset(), dst, 0, entry.keySize());
        }
    }
}
