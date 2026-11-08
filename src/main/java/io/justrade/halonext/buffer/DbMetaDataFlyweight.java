/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import static io.justrade.halonext.buffer.Format.BYTE_ORDER;
import static io.justrade.halonext.buffer.Format.CHECKSUM_SIZE;

import org.agrona.MutableDirectBuffer;

/**
 * Zero-copy flyweight for the DB metadata block stored in the META file
 * (halo-next Format v1). Fixed size, no trailer.
 *
 * <p>Layout (19 bytes, little-endian):
 *
 * <pre>
 *   crc32c(0,4) version(4,1) open(5,1) sequenceNumber(6,8) ioError(14,1)
 *   maxFileSize(15,4)
 * </pre>
 *
 * <p>The {@code open} and {@code ioError} flags gate clean-vs-dirty shutdown
 * detection. The CRC32C covers every byte after the checksum field.
 */
public final class DbMetaDataFlyweight {

    public static final int CHECKSUM_OFFSET = 0;
    public static final int VERSION_OFFSET = 4;
    public static final int OPEN_OFFSET = 5;
    public static final int SEQUENCE_NUMBER_OFFSET = 6;
    public static final int IO_ERROR_OFFSET = 14;
    public static final int MAX_FILE_SIZE_OFFSET = 15;
    public static final int LENGTH = 19;

    private MutableDirectBuffer buffer;
    private int offset;

    public DbMetaDataFlyweight wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int version() {
        return buffer.getByte(offset + VERSION_OFFSET) & 0xFF;
    }

    public DbMetaDataFlyweight version(final int version) {
        buffer.putByte(offset + VERSION_OFFSET, (byte) version);
        return this;
    }

    public boolean open() {
        return buffer.getByte(offset + OPEN_OFFSET) != 0;
    }

    public DbMetaDataFlyweight open(final boolean open) {
        buffer.putByte(offset + OPEN_OFFSET, (byte) (open ? 1 : 0));
        return this;
    }

    public long sequenceNumber() {
        return buffer.getLong(offset + SEQUENCE_NUMBER_OFFSET, BYTE_ORDER);
    }

    public DbMetaDataFlyweight sequenceNumber(final long sequenceNumber) {
        buffer.putLong(offset + SEQUENCE_NUMBER_OFFSET, sequenceNumber, BYTE_ORDER);
        return this;
    }

    public boolean ioError() {
        return buffer.getByte(offset + IO_ERROR_OFFSET) != 0;
    }

    public DbMetaDataFlyweight ioError(final boolean ioError) {
        buffer.putByte(offset + IO_ERROR_OFFSET, (byte) (ioError ? 1 : 0));
        return this;
    }

    public int maxFileSize() {
        return buffer.getInt(offset + MAX_FILE_SIZE_OFFSET, BYTE_ORDER);
    }

    public DbMetaDataFlyweight maxFileSize(final int maxFileSize) {
        buffer.putInt(offset + MAX_FILE_SIZE_OFFSET, maxFileSize, BYTE_ORDER);
        return this;
    }

    public long checksum() {
        return buffer.getInt(offset + CHECKSUM_OFFSET, BYTE_ORDER) & 0xFFFF_FFFFL;
    }

    /** Computes the CRC32C over every byte after the checksum field. */
    public long computeChecksum() {
        return Checksums.crc32c(buffer, offset + CHECKSUM_SIZE, LENGTH - CHECKSUM_SIZE);
    }

    /** Computes and writes the checksum. Call after all other fields are set. */
    public DbMetaDataFlyweight storeChecksum() {
        buffer.putInt(offset + CHECKSUM_OFFSET, (int) computeChecksum(), BYTE_ORDER);
        return this;
    }

    public boolean verifyChecksum() {
        return computeChecksum() == checksum();
    }
}
