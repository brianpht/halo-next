/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbDirectoryIT {

    @Test
    void discoversFilesByIdSorted(@TempDir final Path dir) throws Exception {
        final DbDirectory directory = DbDirectory.open(dir);
        assertFalse(directory.metaFileExists());

        try (DataFile d2 = DataFile.create(dir, 2, Integer.MAX_VALUE, -1, false);
                DataFile d0 = DataFile.create(dir, 0, Integer.MAX_VALUE, -1, false);
                DataFile d10 = DataFile.create(dir, 10, Integer.MAX_VALUE, -1, false);
                IndexFile i0 = IndexFile.create(dir, 0, -1, false);
                IndexFile i2 = IndexFile.create(dir, 2, -1, false);
                TombstoneFile t5 = TombstoneFile.create(dir, 5, Integer.MAX_VALUE, -1, false)) {
            d2.appendRecord(new byte[] {1}, new byte[] {2}, 1L);
            d0.appendRecord(new byte[] {1}, new byte[] {2}, 1L);
            d10.appendRecord(new byte[] {1}, new byte[] {2}, 1L);
            i0.write(new byte[] {1}, 20, 0, 1L);
            i2.write(new byte[] {1}, 20, 0, 1L);
            t5.write(new byte[] {1}, 1L);
        }

        assertArrayEquals(new int[] {0, 2, 10}, directory.listDataFileIds());
        assertArrayEquals(new int[] {0, 2}, directory.listIndexFileIds());
        assertArrayEquals(new int[] {5}, directory.listTombstoneFileIds());
    }

    @Test
    void openCreatesMissingDirectory(@TempDir final Path dir) throws Exception {
        final Path nested = dir.resolve("a").resolve("b").resolve("db");
        final DbDirectory directory = DbDirectory.open(nested);
        assertTrue(java.nio.file.Files.isDirectory(directory.path()));
        directory.sync();
    }
}
