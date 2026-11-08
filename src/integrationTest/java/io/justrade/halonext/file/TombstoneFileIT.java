/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TombstoneFileIT {

    private static byte[] key(final int i) {
        return String.format("tomb%05d", i).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writeAndReplay(@TempDir final Path dir) throws Exception {
        final int count = 200;
        try (TombstoneFile file = TombstoneFile.create(dir, 0, Integer.MAX_VALUE, -1, false)) {
            for (int i = 0; i < count; i++) {
                file.write(key(i), i + 1L);
            }
        }

        try (TombstoneFile file = TombstoneFile.openForRead(dir, 0)) {
            final TombstoneFile.TombstoneFileIterator it = file.iterator();
            int i = 0;
            final byte[] scratch = new byte[key(0).length];
            while (it.next()) {
                it.copyKey(scratch);
                assertArrayEquals(key(i), scratch);
                assertEquals(i + 1L, it.sequenceNumber());
                i++;
            }
            assertEquals(count, i);
        }
    }

    @Test
    void rolloverHonoursMaxFileSize(@TempDir final Path dir) throws Exception {
        final byte[] key = key(0);
        final int entryLength = 14 + key.length;
        try (TombstoneFile file = TombstoneFile.create(dir, 0, entryLength * 2, -1, false)) {
            assertTrue(file.hasCapacityFor(entryLength));
            file.write(key, 1L);
            file.write(key, 2L);
            assertFalse(file.hasCapacityFor(entryLength));
        }
    }

    @Test
    void corruptionStopsIteration(@TempDir final Path dir) throws Exception {
        final int count = 5;
        final int entryLength = 14 + key(0).length;
        try (TombstoneFile file = TombstoneFile.create(dir, 0, Integer.MAX_VALUE, -1, false)) {
            for (int i = 0; i < count; i++) {
                file.write(key(i), i + 1L);
            }
        }

        final int offset = 2 * entryLength + 14 + 1;
        try (FileChannel channel = FileChannel.open(
                dir.resolve(FileNames.tombstoneFile(0)), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, offset);
            one.flip();
            channel.write(ByteBuffer.wrap(new byte[] {(byte) (one.get(0) ^ 0xFF)}), offset);
        }

        try (TombstoneFile file = TombstoneFile.openForRead(dir, 0)) {
            final TombstoneFile.TombstoneFileIterator it = file.iterator();
            int n = 0;
            while (it.next()) {
                n++;
            }
            assertEquals(2, n);
        }
    }
}
