/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import static io.justrade.halonext.buffer.Format.BYTE_ORDER;
import static io.justrade.halonext.buffer.Format.CHECKSUM_SIZE;

import org.agrona.MutableDirectBuffer;

/**
 * Zero-copy flyweight for an index-file entry (halo-next Format v1).
 *
 * <p>Header layout (22 bytes, little-endian), followed by the variable-length key:
 *
 * <pre>
 *   crc32c(0,4) version(4,1) keySize(5,1) recordSize(6,4) recordOffset(10,4)
 *   sequenceNumber(14,8) key[keySize]
 * </pre>
 *
 * <p>{@code recordSize} and {@code recordOffset} locate the record inside its data
 * file; they are not the length of this index entry. The CRC32C covers the header
 * tail and the key.
 */
public final class IndexEntryFlyweight {

    public static final int CHECKSUM_OFFSET = 0;
    public static final int VERSION_OFFSET = 4;
    public static final int KEY_SIZE_OFFSET = 5;
    public static final int RECORD_SIZE_OFFSET = 6;
    public static final int RECORD_OFFSET_OFFSET = 10;
    public static final int SEQUENCE_NUMBER_OFFSET = 14;
    public static final int HEADER_LENGTH = 22;

    private MutableDirectBuffer buffer;
    private int offset;

    public IndexEntryFlyweight wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int version() {
        return buffer.getByte(offset + VERSION_OFFSET) & 0xFF;
    }

    public IndexEntryFlyweight version(final int version) {
        buffer.putByte(offset + VERSION_OFFSET, (byte) version);
        return this;
    }

    public int keySize() {
        return buffer.getByte(offset + KEY_SIZE_OFFSET) & 0xFF;
    }

    public IndexEntryFlyweight keySize(final int keySize) {
        buffer.putByte(offset + KEY_SIZE_OFFSET, (byte) keySize);
        return this;
    }

    public int recordSize() {
        return buffer.getInt(offset + RECORD_SIZE_OFFSET, BYTE_ORDER);
    }

    public IndexEntryFlyweight recordSize(final int recordSize) {
        buffer.putInt(offset + RECORD_SIZE_OFFSET, recordSize, BYTE_ORDER);
        return this;
    }

    public int recordOffset() {
        return buffer.getInt(offset + RECORD_OFFSET_OFFSET, BYTE_ORDER);
    }

    public IndexEntryFlyweight recordOffset(final int recordOffset) {
        buffer.putInt(offset + RECORD_OFFSET_OFFSET, recordOffset, BYTE_ORDER);
        return this;
    }

    public long sequenceNumber() {
        return buffer.getLong(offset + SEQUENCE_NUMBER_OFFSET, BYTE_ORDER);
    }

    public IndexEntryFlyweight sequenceNumber(final long sequenceNumber) {
        buffer.putLong(offset + SEQUENCE_NUMBER_OFFSET, sequenceNumber, BYTE_ORDER);
        return this;
    }

    public long checksum() {
        return buffer.getInt(offset + CHECKSUM_OFFSET, BYTE_ORDER) & 0xFFFF_FFFFL;
    }

    public int keyOffset() {
        return offset + HEADER_LENGTH;
    }

    public int entryLength() {
        return HEADER_LENGTH + keySize();
    }

    /** Computes the CRC32C over the header tail and the key. */
    public long computeChecksum() {
        return Checksums.crc32c(buffer, offset + CHECKSUM_SIZE, entryLength() - CHECKSUM_SIZE);
    }

    /** Computes and writes the checksum into the header. Call after the key is written. */
    public IndexEntryFlyweight storeChecksum() {
        buffer.putInt(offset + CHECKSUM_OFFSET, (int) computeChecksum(), BYTE_ORDER);
        return this;
    }

    public boolean verifyChecksum() {
        return computeChecksum() == checksum();
    }
}
