/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.index;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * One off-heap hash-table segment with chained buckets and a per-segment spin
 * lock. Keys collide into a bucket chosen by the low bits of the key hash; the
 * 64-bit hash is stored in each entry so rehashing never recomputes it.
 *
 * <p>Off-heap entry layout:
 *
 * <pre>
 *   next(0,8) hash(8,8) keyLen(16,1) meta(17,20) key(37,keyLen)
 * </pre>
 */
final class IndexSegment {

    static final int ENTRY_NEXT = 0;
    static final int ENTRY_HASH = 8;
    static final int ENTRY_KEY_LEN = 16;
    static final int ENTRY_META = 17;
    static final int ENTRY_KEY = ENTRY_META + RecordLocation.SIZE;

    static final int MAX_TABLE_SIZE = 1 << 30;

    private static final long BUCKET_BYTES = Long.BYTES;

    private static final VarHandle LOCK;

    static {
        try {
            LOCK = MethodHandles.lookup().findVarHandle(IndexSegment.class, "lock", int.class);
        } catch (final ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @SuppressWarnings("unused")
    private volatile int lock;

    private final NativeAllocator allocator;
    private final float loadFactor;

    private long tableAddr;
    private int tableSize;
    private int threshold;
    private int size;

    IndexSegment(final NativeAllocator allocator, final int initialCapacity, final float loadFactor) {
        this.allocator = allocator;
        this.loadFactor = loadFactor;
        this.tableSize = initialCapacity;
        this.tableAddr = allocator.allocateZeroed((long) initialCapacity * BUCKET_BYTES);
        this.threshold = (int) (initialCapacity * loadFactor);
    }

    boolean get(final byte[] key, final long hash, final RecordLocation dst) {
        acquire();
        try {
            for (long cur = firstOf(hash); cur != 0; cur = Mem.getLong(cur + ENTRY_NEXT)) {
                if (matches(cur, key)) {
                    dst.readFrom(cur + ENTRY_META);
                    return true;
                }
            }
            return false;
        } finally {
            release();
        }
    }

    boolean containsKey(final byte[] key, final long hash) {
        acquire();
        try {
            for (long cur = firstOf(hash); cur != 0; cur = Mem.getLong(cur + ENTRY_NEXT)) {
                if (matches(cur, key)) {
                    return true;
                }
            }
            return false;
        } finally {
            release();
        }
    }

    /** Inserts or overwrites. Returns true when a new key was added. */
    boolean put(
            final byte[] key,
            final long hash,
            final int fileId,
            final int valueOffset,
            final int valueSize,
            final long sequenceNumber,
            final boolean onlyIfAbsent) {
        acquire();
        try {
            long bucket = bucketAddr(hash);
            for (long cur = Mem.getLong(bucket); cur != 0; cur = Mem.getLong(cur + ENTRY_NEXT)) {
                if (matches(cur, key)) {
                    if (!onlyIfAbsent) {
                        RecordLocation.write(cur + ENTRY_META, fileId, valueOffset, valueSize, sequenceNumber);
                    }
                    return false;
                }
            }
            if (size >= threshold) {
                rehash();
                bucket = bucketAddr(hash);
            }
            final long entry = allocator.allocate(ENTRY_KEY + key.length);
            Mem.putLong(entry + ENTRY_NEXT, Mem.getLong(bucket));
            Mem.putLong(entry + ENTRY_HASH, hash);
            Mem.putByte(entry + ENTRY_KEY_LEN, (byte) key.length);
            RecordLocation.write(entry + ENTRY_META, fileId, valueOffset, valueSize, sequenceNumber);
            Mem.copyFromArray(key, 0, entry + ENTRY_KEY, key.length);
            Mem.putLong(bucket, entry);
            size++;
            return true;
        } finally {
            release();
        }
    }

    /** Overwrites only if the current entry's sequence number equals {@code expectedSequenceNumber}. */
    boolean replace(
            final byte[] key,
            final long hash,
            final long expectedSequenceNumber,
            final int fileId,
            final int valueOffset,
            final int valueSize,
            final long newSequenceNumber) {
        acquire();
        try {
            for (long cur = firstOf(hash); cur != 0; cur = Mem.getLong(cur + ENTRY_NEXT)) {
                if (matches(cur, key)) {
                    if (RecordLocation.sequenceNumber(cur + ENTRY_META) == expectedSequenceNumber) {
                        RecordLocation.write(cur + ENTRY_META, fileId, valueOffset, valueSize, newSequenceNumber);
                        return true;
                    }
                    return false;
                }
            }
            return false;
        } finally {
            release();
        }
    }

    boolean remove(final byte[] key, final long hash) {
        acquire();
        try {
            final long bucket = bucketAddr(hash);
            long prev = 0;
            long cur = Mem.getLong(bucket);
            while (cur != 0) {
                final long next = Mem.getLong(cur + ENTRY_NEXT);
                if (matches(cur, key)) {
                    if (prev == 0) {
                        Mem.putLong(bucket, next);
                    } else {
                        Mem.putLong(prev + ENTRY_NEXT, next);
                    }
                    allocator.free(cur, ENTRY_KEY + keyLen(cur));
                    size--;
                    return true;
                }
                prev = cur;
                cur = next;
            }
            return false;
        } finally {
            release();
        }
    }

    int size() {
        return size;
    }

    void close() {
        acquire();
        try {
            if (tableAddr == 0) {
                return;
            }
            for (int b = 0; b < tableSize; b++) {
                long cur = Mem.getLong(tableAddr + (long) b * BUCKET_BYTES);
                while (cur != 0) {
                    final long next = Mem.getLong(cur + ENTRY_NEXT);
                    allocator.free(cur, ENTRY_KEY + keyLen(cur));
                    cur = next;
                }
            }
            allocator.free(tableAddr, (long) tableSize * BUCKET_BYTES);
            tableAddr = 0;
            tableSize = 0;
            size = 0;
        } finally {
            release();
        }
    }

    private void rehash() {
        final int newSize = tableSize << 1;
        if (newSize <= 0 || newSize > MAX_TABLE_SIZE) {
            return;
        }
        final long newAddr = allocator.allocateZeroed((long) newSize * BUCKET_BYTES);
        final int newMask = newSize - 1;
        for (int b = 0; b < tableSize; b++) {
            long cur = Mem.getLong(tableAddr + (long) b * BUCKET_BYTES);
            while (cur != 0) {
                final long next = Mem.getLong(cur + ENTRY_NEXT);
                final long hash = Mem.getLong(cur + ENTRY_HASH);
                final long newBucket = newAddr + ((hash & newMask) * BUCKET_BYTES);
                Mem.putLong(cur + ENTRY_NEXT, Mem.getLong(newBucket));
                Mem.putLong(newBucket, cur);
                cur = next;
            }
        }
        allocator.free(tableAddr, (long) tableSize * BUCKET_BYTES);
        tableAddr = newAddr;
        tableSize = newSize;
        threshold = (int) (newSize * loadFactor);
    }

    private long firstOf(final long hash) {
        return Mem.getLong(bucketAddr(hash));
    }

    private long bucketAddr(final long hash) {
        return tableAddr + ((hash & (tableSize - 1)) * BUCKET_BYTES);
    }

    private boolean matches(final long entry, final byte[] key) {
        return keyLen(entry) == key.length && Mem.keyEquals(key, key.length, entry + ENTRY_KEY);
    }

    private static int keyLen(final long entry) {
        return Mem.getByte(entry + ENTRY_KEY_LEN) & 0xFF;
    }

    private void acquire() {
        while (!LOCK.compareAndSet(this, 0, 1)) {
            Thread.onSpinWait();
        }
    }

    private void release() {
        LOCK.setRelease(this, 0);
    }
}
