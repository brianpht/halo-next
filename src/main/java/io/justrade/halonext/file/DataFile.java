/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import io.justrade.halonext.buffer.Format;
import io.justrade.halonext.buffer.RecordFlyweight;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * An append-only data file. Records are appended sequentially; values are read by
 * random access at a known offset (read amplification = 1). Immutable once rolled;
 * only the current write file is mutable.
 */
public final class DataFile implements AutoCloseable {

    private final int fileId;
    private final Path path;
    private final FileChannel channel;
    private final int maxFileSize;
    private final long flushDataSizeBytes;
    private final boolean syncWrite;

    private final AppendBuffer appendBuffer = new AppendBuffer();
    private final RecordFlyweight record = new RecordFlyweight();

    private int writeOffset;
    private long unflushedBytes;

    private DataFile(
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

    /** Creates a new, empty data file. Fails if the file already exists. */
    public static DataFile create(
            final Path dir,
            final int fileId,
            final int maxFileSize,
            final long flushDataSizeBytes,
            final boolean syncWrite)
            throws IOException {
        final Path file = dir.resolve(FileNames.dataFile(fileId));
        final FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        return new DataFile(fileId, file, channel, maxFileSize, flushDataSizeBytes, syncWrite, 0);
    }

    /** Opens an existing data file for reading. */
    public static DataFile openForRead(final Path dir, final int fileId) throws IOException {
        final Path file = dir.resolve(FileNames.dataFile(fileId));
        final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        return new DataFile(fileId, file, channel, Integer.MAX_VALUE, -1, false, (int) channel.size());
    }

    public int fileId() {
        return fileId;
    }

    public Path path() {
        return path;
    }

    public int writeOffset() {
        return writeOffset;
    }

    /** Returns true if a record of {@code recordLength} bytes fits without exceeding the size limit. */
    public boolean hasCapacityFor(final int recordLength) {
        return (long) writeOffset + recordLength <= maxFileSize;
    }

    /**
     * Appends a record and returns the offset at which it was written. The value
     * offset for the in-memory index is {@code recordOffset + HEADER_LENGTH + key.length}.
     */
    public int appendRecord(final byte[] key, final byte[] value, final long sequenceNumber) throws IOException {
        final int recordLength = RecordFlyweight.HEADER_LENGTH + key.length + value.length;
        final UnsafeBuffer encode = appendBuffer.buffer(recordLength);
        final RecordFlyweight fly = record.wrap(encode, 0);
        fly.version(Format.DATA_FILE_VERSION)
                .keySize(key.length)
                .valueSize(value.length)
                .sequenceNumber(sequenceNumber);
        encode.putBytes(fly.keyOffset(), key);
        encode.putBytes(fly.valueOffset(), value);
        fly.storeChecksum();

        writeFully(appendBuffer.readyForWrite(recordLength));
        final int recordOffset = writeOffset;
        writeOffset += recordLength;
        unflushedBytes += recordLength;
        flushIfNeeded();
        return recordOffset;
    }

    /** Reads {@code valueSize} bytes of a value at {@code valueOffset} into a new array. */
    public byte[] readValue(final int valueOffset, final int valueSize) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(valueSize);
        readFully(buffer, valueOffset);
        return buffer.array();
    }

    /**
     * Reads a full record at {@code recordOffset} into {@code dst} (which must be a
     * direct buffer) and returns the record length. Wrap a {@link RecordFlyweight}
     * over {@code dst} at offset 0 to verify the checksum and read fields.
     */
    public int readRecordInto(final int recordOffset, final UnsafeBuffer dst) throws IOException {
        final ByteBuffer dstBuffer = dst.byteBuffer();
        dstBuffer.clear().limit(RecordFlyweight.HEADER_LENGTH);
        readFully(dstBuffer, recordOffset);

        final RecordFlyweight fly = record.wrap(dst, 0);
        final int recordLength = RecordFlyweight.HEADER_LENGTH + fly.keySize() + fly.valueSize();

        dstBuffer.clear().position(RecordFlyweight.HEADER_LENGTH).limit(recordLength);
        readFully(dstBuffer, recordOffset + RecordFlyweight.HEADER_LENGTH);
        return recordLength;
    }

    public void flush() throws IOException {
        channel.force(false);
        unflushedBytes = 0;
    }

    @Override
    public void close() throws IOException {
        if (channel.isOpen()) {
            flush();
            channel.close();
        }
    }

    /** Opens a forward iterator over all valid records; stops at the first corrupt or torn record. */
    public RecordIterator iterator() throws IOException {
        return new RecordIterator(FileChannels.readAll(channel));
    }

    private void flushIfNeeded() throws IOException {
        if (syncWrite || (flushDataSizeBytes >= 0 && unflushedBytes > flushDataSizeBytes)) {
            flush();
        }
    }

    private void writeFully(final ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private void readFully(final ByteBuffer buffer, final long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            final int read = channel.read(buffer, pos);
            if (read < 0) {
                throw new IOException("Unexpected end of file reading " + path + " at " + pos);
            }
            pos += read;
        }
        buffer.flip();
    }

    /** Forward cursor over records. Reuses one flyweight; allocation-free per step. */
    public static final class RecordIterator {

        private final UnsafeBuffer buffer;
        private final int limit;
        private final RecordFlyweight record = new RecordFlyweight();
        private int position;
        private int recordStart;

        RecordIterator(final UnsafeBuffer buffer) {
            this.buffer = buffer;
            this.limit = buffer.capacity();
        }

        public boolean next() {
            if (position + RecordFlyweight.HEADER_LENGTH > limit) {
                return false;
            }
            final RecordFlyweight fly = record.wrap(buffer, position);
            final int recordLength = RecordFlyweight.HEADER_LENGTH + fly.keySize() + fly.valueSize();
            if (position + recordLength > limit || !fly.verifyChecksum()) {
                return false;
            }
            recordStart = position;
            position += recordLength;
            return true;
        }

        public int recordOffset() {
            return recordStart;
        }

        public int keySize() {
            return record.keySize();
        }

        public int valueSize() {
            return record.valueSize();
        }

        public long sequenceNumber() {
            return record.sequenceNumber();
        }

        public void copyKey(final byte[] dst) {
            buffer.getBytes(record.keyOffset(), dst, 0, record.keySize());
        }

        public void copyValue(final byte[] dst) {
            buffer.getBytes(record.valueOffset(), dst, 0, record.valueSize());
        }
    }
}
