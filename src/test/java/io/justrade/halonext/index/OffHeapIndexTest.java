/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class OffHeapIndexTest {

    private static byte[] key(final int i) {
        return ("key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putGetOverwriteRemove() {
        final NativeAllocator allocator = new NativeAllocator();
        try (OffHeapIndex index = new OffHeapIndex(1024, 8, 0.75f, allocator)) {
            final RecordLocation loc = new RecordLocation();

            assertFalse(index.get(key(1), loc));
            assertTrue(index.put(key(1), 3, 100, 50, 7L));
            assertTrue(index.get(key(1), loc));
            assertEquals(3, loc.fileId());
            assertEquals(100, loc.valueOffset());
            assertEquals(50, loc.valueSize());
            assertEquals(7L, loc.sequenceNumber());
            assertEquals(1, index.size());

            assertFalse(index.put(key(1), 4, 200, 60, 8L));
            assertTrue(index.get(key(1), loc));
            assertEquals(4, loc.fileId());
            assertEquals(8L, loc.sequenceNumber());
            assertEquals(1, index.size());

            assertTrue(index.remove(key(1)));
            assertFalse(index.get(key(1), loc));
            assertEquals(0, index.size());
        }
        assertEquals(0L, allocator.allocatedBytes());
    }

    @Test
    void putIfAbsent() {
        final NativeAllocator allocator = new NativeAllocator();
        try (OffHeapIndex index = new OffHeapIndex(1024, 8, 0.75f, allocator)) {
            final RecordLocation loc = new RecordLocation();
            assertTrue(index.putIfAbsent(key(1), 1, 10, 5, 1L));
            assertFalse(index.putIfAbsent(key(1), 2, 20, 6, 2L));
            assertTrue(index.get(key(1), loc));
            assertEquals(1, loc.fileId());
            assertEquals(1L, loc.sequenceNumber());
        }
        assertEquals(0L, allocator.allocatedBytes());
    }

    @Test
    void replaceBySequence() {
        final NativeAllocator allocator = new NativeAllocator();
        try (OffHeapIndex index = new OffHeapIndex(1024, 8, 0.75f, allocator)) {
            final RecordLocation loc = new RecordLocation();
            assertTrue(index.put(key(1), 1, 10, 5, 100L));

            assertFalse(index.replace(key(1), 999L, 2, 20, 6, 101L));
            assertTrue(index.get(key(1), loc));
            assertEquals(1, loc.fileId());

            assertTrue(index.replace(key(1), 100L, 2, 20, 6, 101L));
            assertTrue(index.get(key(1), loc));
            assertEquals(2, loc.fileId());
            assertEquals(20, loc.valueOffset());
            assertEquals(101L, loc.sequenceNumber());

            assertFalse(index.replace(key(2), 1L, 0, 0, 0, 2L));
        }
        assertEquals(0L, allocator.allocatedBytes());
    }

    @Test
    void bulkWithRehash() {
        final NativeAllocator allocator = new NativeAllocator();
        final int n = 50_000;
        try (OffHeapIndex index = new OffHeapIndex(1024, 8, 0.75f, allocator)) {
            final RecordLocation loc = new RecordLocation();
            for (int i = 0; i < n; i++) {
                assertTrue(index.put(key(i), i % 7, i * 20, 20, i + 1L));
            }
            assertEquals(n, index.size());
            for (int i = 0; i < n; i++) {
                assertTrue(index.get(key(i), loc));
                assertEquals(i % 7, loc.fileId());
                assertEquals(i * 20, loc.valueOffset());
                assertEquals(i + 1L, loc.sequenceNumber());
            }
            for (int i = 0; i < n; i++) {
                assertFalse(index.put(key(i), 0, 0, 1, i + 1000L));
            }
            for (int i = 0; i < n; i++) {
                assertTrue(index.get(key(i), loc));
                assertEquals(i + 1000L, loc.sequenceNumber());
            }
            for (int i = 0; i < n; i++) {
                assertTrue(index.remove(key(i)));
            }
            assertEquals(0, index.size());
        }
        assertEquals(0L, allocator.allocatedBytes());
    }

    @Property
    void singleKeyRoundTrip(
            @ForAll @Size(min = 1, max = 255) byte[] k,
            @ForAll int fileId,
            @ForAll int valueOffset,
            @ForAll int valueSize,
            @ForAll long sequenceNumber) {
        final NativeAllocator allocator = new NativeAllocator();
        try (OffHeapIndex index = new OffHeapIndex(64, 4, 0.75f, allocator)) {
            final RecordLocation loc = new RecordLocation();
            assertTrue(index.put(k, fileId, valueOffset, valueSize, sequenceNumber));
            assertTrue(index.get(k, loc));
            assertEquals(fileId, loc.fileId());
            assertEquals(valueOffset, loc.valueOffset());
            assertEquals(valueSize, loc.valueSize());
            assertEquals(sequenceNumber, loc.sequenceNumber());
            assertTrue(index.remove(k));
        }
        assertEquals(0L, allocator.allocatedBytes());
    }
}
