/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import io.justrade.halonext.buffer.Format;
import io.justrade.halonext.buffer.TombstoneFlyweight;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Append-only tombstone file holding delete markers. Merged and reconciled against
 * the index during open and compaction.
 */
public final class TombstoneFile implements AutoCloseable {

    private final int fileId;
    private final Path path;
    private final FileChannel channel;
    private final int maxFileSize;
    private final long flushDataSizeBytes;
    private final boolean syncWrite;

    private final AppendBuffer appendBuffer = new AppendBuffer();
    private final TombstoneFlyweight entry = new TombstoneFlyweight();

    private int writeOffset;
    private long unflushedBytes;

    private TombstoneFile(
            final int fileId,
            final Path path,
            final FileChannel channel,
            final int maxFileSize,
            final long flushDataSizeBytes,
            final boolean syncWrite,
            final int initialOffset) {
        this.fileId = fileId;
        this.path = path;
        this.channel = channel;
        this.maxFileSize = maxFileSize;
        this.flushDataSizeBytes = flushDataSizeBytes;
        this.syncWrite = syncWrite;
        this.writeOffset = initialOffset;
    }

    public static TombstoneFile create(
            final Path dir,
            final int fileId,
            final int maxFileSize,
            final long flushDataSizeBytes,
            final boolean syncWrite)
            throws IOException {
        final Path file = dir.resolve(FileNames.tombstoneFile(fileId));
        final FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ);
        return new TombstoneFile(fileId, file, channel, maxFileSize, flushDataSizeBytes, syncWrite, 0);
    }

    public static TombstoneFile openForRead(final Path dir, final int fileId) throws IOException {
        final Path file = dir.resolve(FileNames.tombstoneFile(fileId));
        final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        return new TombstoneFile(fileId, file, channel, Integer.MAX_VALUE, -1, false, (int) channel.size());
    }

    public int fileId() {
        return fileId;
    }

    public int writeOffset() {
        return writeOffset;
    }

    public boolean hasCapacityFor(final int entryLength) {
        return (long) writeOffset + entryLength <= maxFileSize;
    }

    public void write(final byte[] key, final long sequenceNumber) throws IOException {
        final int entryLength = TombstoneFlyweight.HEADER_LENGTH + key.length;
        final UnsafeBuffer encode = appendBuffer.buffer(entryLength);
        final TombstoneFlyweight fly = entry.wrap(encode, 0);
        fly.version(Format.TOMBSTONE_FILE_VERSION)
                .sequenceNumber(sequenceNumber)
                .keySize(key.length);
        encode.putBytes(fly.keyOffset(), key);
        fly.storeChecksum();

        writeFully(appendBuffer.readyForWrite(entryLength));
        writeOffset += entryLength;
        unflushedBytes += entryLength;
        if (syncWrite || (flushDataSizeBytes >= 0 && unflushedBytes > flushDataSizeBytes)) {
            flush();
        }
    }

    /** Opens a replay iterator over all valid tombstones; stops at the first corrupt or torn entry. */
    public TombstoneFileIterator iterator() throws IOException {
        return new TombstoneFileIterator(FileChannels.readAll(channel));
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

    /** Forward cursor over tombstone entries. Reuses one flyweight; allocation-free per step. */
    public static final class TombstoneFileIterator {

        private final UnsafeBuffer buffer;
        private final int limit;
        private final TombstoneFlyweight entry = new TombstoneFlyweight();
        private int position;

        TombstoneFileIterator(final UnsafeBuffer buffer) {
            this.buffer = buffer;
            this.limit = buffer.capacity();
        }

        public boolean next() {
            if (position + TombstoneFlyweight.HEADER_LENGTH > limit) {
                return false;
            }
            final TombstoneFlyweight fly = entry.wrap(buffer, position);
            final int entryLength = TombstoneFlyweight.HEADER_LENGTH + fly.keySize();
            if (position + entryLength > limit || !fly.verifyChecksum()) {
                return false;
            }
            position += entryLength;
            return true;
        }

        public int keySize() {
            return entry.keySize();
        }

        public long sequenceNumber() {
            return entry.sequenceNumber();
        }

        public void copyKey(final byte[] dst) {
            buffer.getBytes(entry.keyOffset(), dst, 0, entry.keySize());
        }
    }
}
