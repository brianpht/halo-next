/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justrade.halonext.buffer.RecordFlyweight;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataFileIT {

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void appendAndReadValuesBack(@TempDir final Path dir) throws Exception {
        final int count = 500;
        final int[] valueOffsets = new int[count];
        final int[] valueSizes = new int[count];

        try (DataFile file = DataFile.create(dir, 0, Integer.MAX_VALUE, -1, false)) {
            for (int i = 0; i < count; i++) {
                final byte[] key = bytes("key-" + i);
                final byte[] value = bytes("value-" + i + "-payload");
                final int recordOffset = file.appendRecord(key, value, i + 1L);
                valueOffsets[i] = recordOffset + RecordFlyweight.HEADER_LENGTH + key.length;
                valueSizes[i] = value.length;
            }
        }

        try (DataFile file = DataFile.openForRead(dir, 0)) {
            for (int i = 0; i < count; i++) {
                final byte[] expected = bytes("value-" + i + "-payload");
                assertArrayEquals(expected, file.readValue(valueOffsets[i], valueSizes[i]));
            }
        }
    }

    @Test
    void rolloverHonoursMaxFileSize(@TempDir final Path dir) throws Exception {
        final byte[] key = bytes("k");
        final byte[] value = bytes("0123456789");
        final int recordLength = RecordFlyweight.HEADER_LENGTH + key.length + value.length;

        try (DataFile file = DataFile.create(dir, 0, recordLength * 2, -1, false)) {
            assertTrue(file.hasCapacityFor(recordLength));
            file.appendRecord(key, value, 1L);
            assertTrue(file.hasCapacityFor(recordLength));
            file.appendRecord(key, value, 2L);
            assertFalse(file.hasCapacityFor(recordLength));
        }
    }

    @Test
    void corruptedValueFailsChecksum(@TempDir final Path dir) throws Exception {
        final byte[] key = bytes("the-key");
        final byte[] value = bytes("the-value-payload");
        final int recordOffset;
        try (DataFile file = DataFile.create(dir, 0, Integer.MAX_VALUE, -1, false)) {
            recordOffset = file.appendRecord(key, value, 1L);
        }

        final int valueOffset = recordOffset + RecordFlyweight.HEADER_LENGTH + key.length;
        flipByte(dir.resolve(FileNames.dataFile(0)), valueOffset);

        final UnsafeBuffer dst = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        try (DataFile file = DataFile.openForRead(dir, 0)) {
            final int length = file.readRecordInto(recordOffset, dst);
            assertEquals(RecordFlyweight.HEADER_LENGTH + key.length + value.length, length);
            assertFalse(new RecordFlyweight().wrap(dst, 0).verifyChecksum());
        }
    }

    private static void flipByte(final Path file, final int offset) throws Exception {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, offset);
            one.flip();
            final byte corrupted = (byte) (one.get(0) ^ 0xFF);
            channel.write(ByteBuffer.wrap(new byte[] {corrupted}), offset);
        }
    }
}
