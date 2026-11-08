/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import static io.justrade.halonext.buffer.Format.BYTE_ORDER;
import static io.justrade.halonext.buffer.Format.CHECKSUM_SIZE;

import org.agrona.MutableDirectBuffer;

/**
 * Zero-copy flyweight for a tombstone-file entry (halo-next Format v1).
 *
 * <p>Header layout (14 bytes, little-endian), followed by the variable-length key.
 * Note the key size trails the sequence number to match the original HaloDB field
 * ordering:
 *
 * <pre>
 *   crc32c(0,4) version(4,1) sequenceNumber(5,8) keySize(13,1) key[keySize]
 * </pre>
 *
 * <p>The CRC32C covers the header tail and the key.
 */
public final class TombstoneFlyweight {

    public static final int CHECKSUM_OFFSET = 0;
    public static final int VERSION_OFFSET = 4;
    public static final int SEQUENCE_NUMBER_OFFSET = 5;
    public static final int KEY_SIZE_OFFSET = 13;
    public static final int HEADER_LENGTH = 14;

    private MutableDirectBuffer buffer;
    private int offset;

    public TombstoneFlyweight wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int version() {
        return buffer.getByte(offset + VERSION_OFFSET) & 0xFF;
    }

    public TombstoneFlyweight version(final int version) {
        buffer.putByte(offset + VERSION_OFFSET, (byte) version);
        return this;
    }

    public long sequenceNumber() {
        return buffer.getLong(offset + SEQUENCE_NUMBER_OFFSET, BYTE_ORDER);
    }

    public TombstoneFlyweight sequenceNumber(final long sequenceNumber) {
        buffer.putLong(offset + SEQUENCE_NUMBER_OFFSET, sequenceNumber, BYTE_ORDER);
        return this;
    }

    public int keySize() {
        return buffer.getByte(offset + KEY_SIZE_OFFSET) & 0xFF;
    }

    public TombstoneFlyweight keySize(final int keySize) {
        buffer.putByte(offset + KEY_SIZE_OFFSET, (byte) keySize);
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
    public TombstoneFlyweight storeChecksum() {
        buffer.putInt(offset + CHECKSUM_OFFSET, (int) computeChecksum(), BYTE_ORDER);
        return this;
    }

    public boolean verifyChecksum() {
        return computeChecksum() == checksum();
    }
}
