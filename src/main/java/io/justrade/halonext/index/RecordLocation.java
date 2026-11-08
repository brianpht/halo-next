/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.index;

/**
 * Mutable holder for the in-memory index value: where a record's value lives and
 * the sequence number that versions it. Serialized layout is 20 bytes
 * (little-endian via native order): fileId(0,4) valueOffset(4,4) valueSize(8,4)
 * sequenceNumber(12,8).
 *
 * <p>Used as an allocation-free out-parameter for {@link OffHeapIndex#get}.
 */
public final class RecordLocation {

    static final int SIZE = 20;

    private static final int FILE_ID_OFFSET = 0;
    private static final int VALUE_OFFSET_OFFSET = 4;
    private static final int VALUE_SIZE_OFFSET = 8;
    private static final int SEQUENCE_NUMBER_OFFSET = 12;

    private int fileId;
    private int valueOffset;
    private int valueSize;
    private long sequenceNumber;

    public int fileId() {
        return fileId;
    }

    public int valueOffset() {
        return valueOffset;
    }

    public int valueSize() {
        return valueSize;
    }

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public RecordLocation set(final int fileId, final int valueOffset, final int valueSize, final long sequenceNumber) {
        this.fileId = fileId;
        this.valueOffset = valueOffset;
        this.valueSize = valueSize;
        this.sequenceNumber = sequenceNumber;
        return this;
    }

    void readFrom(final long metaAddr) {
        fileId = Mem.getInt(metaAddr + FILE_ID_OFFSET);
        valueOffset = Mem.getInt(metaAddr + VALUE_OFFSET_OFFSET);
        valueSize = Mem.getInt(metaAddr + VALUE_SIZE_OFFSET);
        sequenceNumber = Mem.getLong(metaAddr + SEQUENCE_NUMBER_OFFSET);
    }

    static void write(
            final long metaAddr,
            final int fileId,
            final int valueOffset,
            final int valueSize,
            final long sequenceNumber) {
        Mem.putInt(metaAddr + FILE_ID_OFFSET, fileId);
        Mem.putInt(metaAddr + VALUE_OFFSET_OFFSET, valueOffset);
        Mem.putInt(metaAddr + VALUE_SIZE_OFFSET, valueSize);
        Mem.putLong(metaAddr + SEQUENCE_NUMBER_OFFSET, sequenceNumber);
    }

    static long sequenceNumber(final long metaAddr) {
        return Mem.getLong(metaAddr + SEQUENCE_NUMBER_OFFSET);
    }
}
