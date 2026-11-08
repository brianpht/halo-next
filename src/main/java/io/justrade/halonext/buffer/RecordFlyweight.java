/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import static io.justrade.halonext.buffer.Format.BYTE_ORDER;
import static io.justrade.halonext.buffer.Format.CHECKSUM_SIZE;

import org.agrona.MutableDirectBuffer;

/**
 * Zero-copy flyweight for a data-file record (halo-next Format v1).
 *
 * <p>Header layout (18 bytes, little-endian), followed by the variable-length key
 * then value laid out contiguously:
 *
 * <pre>
 *   crc32c(0,4) version(4,1) keySize(5,1) valueSize(6,4) sequenceNumber(10,8)
 *   key[keySize] value[valueSize]
 * </pre>
 *
 * <p>The CRC32C covers every byte after the checksum field: the header tail, the
 * key, and the value. The flyweight holds no state beyond the wrapped buffer and
 * offset; call {@link #wrap(MutableDirectBuffer, int)} per record.
 */
public final class RecordFlyweight {

    public static final int CHECKSUM_OFFSET = 0;
    public static final int VERSION_OFFSET = 4;
    public static final int KEY_SIZE_OFFSET = 5;
    public static final int VALUE_SIZE_OFFSET = 6;
    public static final int SEQUENCE_NUMBER_OFFSET = 10;
    public static final int HEADER_LENGTH = 18;

    private MutableDirectBuffer buffer;
    private int offset;

    public RecordFlyweight wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int version() {
        return buffer.getByte(offset + VERSION_OFFSET) & 0xFF;
    }

    public RecordFlyweight version(final int version) {
        buffer.putByte(offset + VERSION_OFFSET, (byte) version);
        return this;
    }

    public int keySize() {
        return buffer.getByte(offset + KEY_SIZE_OFFSET) & 0xFF;
    }

    public RecordFlyweight keySize(final int keySize) {
        buffer.putByte(offset + KEY_SIZE_OFFSET, (byte) keySize);
        return this;
    }

    public int valueSize() {
        return buffer.getInt(offset + VALUE_SIZE_OFFSET, BYTE_ORDER);
    }

    public RecordFlyweight valueSize(final int valueSize) {
        buffer.putInt(offset + VALUE_SIZE_OFFSET, valueSize, BYTE_ORDER);
        return this;
    }

    public long sequenceNumber() {
        return buffer.getLong(offset + SEQUENCE_NUMBER_OFFSET, BYTE_ORDER);
    }

    public RecordFlyweight sequenceNumber(final long sequenceNumber) {
        buffer.putLong(offset + SEQUENCE_NUMBER_OFFSET, sequenceNumber, BYTE_ORDER);
        return this;
    }

    public long checksum() {
        return buffer.getInt(offset + CHECKSUM_OFFSET, BYTE_ORDER) & 0xFFFF_FFFFL;
    }

    public int keyOffset() {
        return offset + HEADER_LENGTH;
    }

    public int valueOffset() {
        return offset + HEADER_LENGTH + keySize();
    }

    public int recordLength() {
        return HEADER_LENGTH + keySize() + valueSize();
    }

    /** Computes the CRC32C over the header tail, key, and value. */
    public long computeChecksum() {
        return Checksums.crc32c(buffer, offset + CHECKSUM_SIZE, recordLength() - CHECKSUM_SIZE);
    }

    /** Computes and writes the checksum into the header. Call after key and value are written. */
    public RecordFlyweight storeChecksum() {
        buffer.putInt(offset + CHECKSUM_OFFSET, (int) computeChecksum(), BYTE_ORDER);
        return this;
    }

    public boolean verifyChecksum() {
        return computeChecksum() == checksum();
    }
}
