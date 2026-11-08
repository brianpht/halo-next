/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.index;

/**
 * Off-heap, segmented hash index from a byte-array key to a {@link RecordLocation}
 * (fileId, valueOffset, valueSize, sequenceNumber). All keys and metadata live in
 * native memory to keep GC pressure near zero.
 *
 * <p>Segments are power-of-two in count (at least {@code 2 x availableProcessors()})
 * and selected from the high bits of the key hash; buckets within a segment are
 * selected from the low bits. Each segment carries its own spin lock, so readers
 * and writers contend only within a segment.
 *
 * <p>Keys may be 1..255 bytes. {@link #get} fills a caller-provided
 * {@link RecordLocation} and is allocation-free.
 */
public final class OffHeapIndex implements AutoCloseable {

    private static final float DEFAULT_LOAD_FACTOR = 0.75f;
    private static final int MIN_SEGMENT_CAPACITY = 16;

    private final IndexSegment[] segments;
    private final int segmentShift;
    private final int segmentMask;
    private final NativeAllocator allocator;

    public OffHeapIndex(final long numberOfRecords) {
        this(numberOfRecords, defaultSegmentCount(), DEFAULT_LOAD_FACTOR, new NativeAllocator());
    }

    public OffHeapIndex(
            final long numberOfRecords,
            final int segmentCount,
            final float loadFactor,
            final NativeAllocator allocator) {
        final int count = Math.max(2, roundUpPowerOfTwo(segmentCount));
        this.allocator = allocator;
        this.segments = new IndexSegment[count];
        this.segmentShift = 64 - Integer.numberOfTrailingZeros(count);
        this.segmentMask = count - 1;
        final int perSegment = perSegmentCapacity(numberOfRecords, count, loadFactor);
        for (int i = 0; i < count; i++) {
            segments[i] = new IndexSegment(allocator, perSegment, loadFactor);
        }
    }

    /** Inserts or overwrites the location for {@code key}. Returns true if the key was new. */
    public boolean put(
            final byte[] key, final int fileId, final int valueOffset, final int valueSize, final long sequenceNumber) {
        final long hash = Hashing64.hash(key);
        return segmentFor(hash).put(key, hash, fileId, valueOffset, valueSize, sequenceNumber, false);
    }

    /** Inserts only if absent. Returns true if the key was inserted. */
    public boolean putIfAbsent(
            final byte[] key, final int fileId, final int valueOffset, final int valueSize, final long sequenceNumber) {
        final long hash = Hashing64.hash(key);
        return segmentFor(hash).put(key, hash, fileId, valueOffset, valueSize, sequenceNumber, true);
    }

    /**
     * Overwrites only if the current entry's sequence number equals
     * {@code expectedSequenceNumber}. Used by compaction to update a location iff the
     * migrated version is still current. Returns true if replaced.
     */
    public boolean replace(
            final byte[] key,
            final long expectedSequenceNumber,
            final int fileId,
            final int valueOffset,
            final int valueSize,
            final long newSequenceNumber) {
        final long hash = Hashing64.hash(key);
        return segmentFor(hash)
                .replace(key, hash, expectedSequenceNumber, fileId, valueOffset, valueSize, newSequenceNumber);
    }

    /** Fills {@code dst} with the location for {@code key}. Returns true if found. */
    public boolean get(final byte[] key, final RecordLocation dst) {
        final long hash = Hashing64.hash(key);
        return segmentFor(hash).get(key, hash, dst);
    }

    public boolean containsKey(final byte[] key) {
        final long hash = Hashing64.hash(key);
        return segmentFor(hash).containsKey(key, hash);
    }

    public boolean remove(final byte[] key) {
        final long hash = Hashing64.hash(key);
        return segmentFor(hash).remove(key, hash);
    }

    public long size() {
        long total = 0;
        for (final IndexSegment segment : segments) {
            total += segment.size();
        }
        return total;
    }

    public long allocatedBytes() {
        return allocator.allocatedBytes();
    }

    @Override
    public void close() {
        for (final IndexSegment segment : segments) {
            segment.close();
        }
    }

    private IndexSegment segmentFor(final long hash) {
        return segments[(int) ((hash >>> segmentShift) & segmentMask)];
    }

    private static int defaultSegmentCount() {
        return Math.max(2, roundUpPowerOfTwo(Runtime.getRuntime().availableProcessors() * 2));
    }

    private static int perSegmentCapacity(final long numberOfRecords, final int segmentCount, final float loadFactor) {
        final long target = (long) Math.ceil(numberOfRecords / (double) segmentCount / loadFactor);
        final long bounded = Math.min(Math.max(target, MIN_SEGMENT_CAPACITY), IndexSegment.MAX_TABLE_SIZE);
        return roundUpPowerOfTwo((int) bounded);
    }

    private static int roundUpPowerOfTwo(final int value) {
        if (value <= 1) {
            return 1;
        }
        final int highest = Integer.highestOneBit(value);
        return highest == value ? value : highest << 1;
    }
}
