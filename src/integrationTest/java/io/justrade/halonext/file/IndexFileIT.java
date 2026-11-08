/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justrade.halonext.buffer.IndexEntryFlyweight;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexFileIT {

    // Fixed-length keys keep entry boundaries deterministic for corruption offsets.
    private static byte[] key(final int i) {
        return String.format("key%05d", i).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writeAndReplayEntries(@TempDir final Path dir) throws Exception {
        final int count = 300;
        try (IndexFile file = IndexFile.create(dir, 0, -1, false)) {
            for (int i = 0; i < count; i++) {
                file.write(key(i), 100 + i, i * 128, i + 1L);
            }
        }

        try (IndexFile file = IndexFile.openForRead(dir, 0)) {
            final IndexFile.IndexFileIterator it = file.iterator();
            int i = 0;
            final byte[] scratch = new byte[key(0).length];
            while (it.next()) {
                assertEquals(key(i).length, it.keySize());
                it.copyKey(scratch);
                assertArrayEquals(key(i), scratch);
                assertEquals(100 + i, it.recordSize());
                assertEquals(i * 128, it.recordOffset());
                assertEquals(i + 1L, it.sequenceNumber());
                i++;
            }
            assertEquals(count, i);
        }
    }

    @Test
    void tornTailStopsIteration(@TempDir final Path dir) throws Exception {
        final int count = 10;
        try (IndexFile file = IndexFile.create(dir, 0, -1, false)) {
            for (int i = 0; i < count; i++) {
                file.write(key(i), 50, i, i + 1L);
            }
        }

        // Drop the last 3 bytes to simulate a torn final append.
        final Path path = dir.resolve(FileNames.indexFile(0));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 3);
        }

        assertEquals(count - 1, countEntries(dir));
    }

    @Test
    void corruptionStopsIteration(@TempDir final Path dir) throws Exception {
        final int count = 6;
        final int entryLength = IndexEntryFlyweight.HEADER_LENGTH + key(0).length;
        try (IndexFile file = IndexFile.create(dir, 0, -1, false)) {
            for (int i = 0; i < count; i++) {
                file.write(key(i), 50, i, i + 1L);
            }
        }

        // Corrupt the key bytes of entry index 3.
        final int offset = 3 * entryLength + IndexEntryFlyweight.HEADER_LENGTH + 1;
        flipByte(dir.resolve(FileNames.indexFile(0)), offset);

        assertEquals(3, countEntries(dir));
    }

    private static int countEntries(final Path dir) throws Exception {
        try (IndexFile file = IndexFile.openForRead(dir, 0)) {
            final IndexFile.IndexFileIterator it = file.iterator();
            int n = 0;
            while (it.next()) {
                n++;
            }
            return n;
        }
    }

    private static void flipByte(final Path file, final int offset) throws Exception {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, offset);
            one.flip();
            channel.write(ByteBuffer.wrap(new byte[] {(byte) (one.get(0) ^ 0xFF)}), offset);
        }
    }
}
