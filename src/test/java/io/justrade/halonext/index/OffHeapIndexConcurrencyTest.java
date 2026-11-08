/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OffHeapIndexConcurrencyTest {

    private static byte[] key(final int i) {
        return ("k" + i).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void concurrentDisjointWritersThenReadBack() throws InterruptedException {
        final NativeAllocator allocator = new NativeAllocator();
        final int threads = 8;
        final int perThread = 20_000;
        final int total = threads * perThread;

        try (OffHeapIndex index = new OffHeapIndex(total, 16, 0.75f, allocator)) {
            final Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                workers[t] = new Thread(() -> {
                    for (int i = 0; i < perThread; i++) {
                        final int id = tid * perThread + i;
                        index.put(key(id), tid, id, 10, id + 1L);
                    }
                });
            }
            for (final Thread w : workers) {
                w.start();
            }
            for (final Thread w : workers) {
                w.join();
            }

            assertEquals(total, index.size());
            final RecordLocation loc = new RecordLocation();
            for (int id = 0; id < total; id++) {
                assertTrue(index.get(key(id), loc));
                assertEquals(id + 1L, loc.sequenceNumber());
            }
        }
        assertEquals(0L, allocator.allocatedBytes());
    }
}
