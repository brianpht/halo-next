/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justrade.halonext.config.HaloDBOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HaloEngineIT {

    private static byte[] key(final int i) {
        return ("key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(final int i) {
        return ("value-" + i + "-payload").getBytes(StandardCharsets.UTF_8);
    }

    private static HaloDBOptions smallFiles() {
        return new HaloDBOptions()
                .setMaxFileSize(256)
                .setMaxTombstoneFileSize(128)
                .setNumberOfRecords(1024);
    }

    @Test
    void putGetOverwriteDelete(@TempDir final Path dir) throws Exception {
        try (HaloEngine engine = HaloEngine.open(dir, smallFiles())) {
            assertNull(engine.get(key(1)));
            assertTrue(engine.put(key(1), value(1)));
            assertArrayEquals(value(1), engine.get(key(1)));
            assertEquals(1, engine.size());

            assertTrue(engine.put(key(1), value(2)));
            assertArrayEquals(value(2), engine.get(key(1)));
            assertEquals(1, engine.size());

            assertTrue(engine.delete(key(1)));
            assertNull(engine.get(key(1)));
            assertFalse(engine.delete(key(1)));
            assertEquals(0, engine.size());
        }
    }

    @Test
    void reopenPersistsAcrossRollover(@TempDir final Path dir) throws Exception {
        final int n = 2000;
        try (HaloEngine engine = HaloEngine.open(dir, smallFiles())) {
            for (int i = 0; i < n; i++) {
                assertTrue(engine.put(key(i), value(i)));
            }
            assertEquals(n, engine.size());
        }

        try (HaloEngine engine = HaloEngine.open(dir, smallFiles())) {
            assertEquals(n, engine.size());
            for (int i = 0; i < n; i++) {
                assertArrayEquals(value(i), engine.get(key(i)), "key " + i);
            }
        }
    }

    @Test
    void deletesSurviveReopen(@TempDir final Path dir) throws Exception {
        final int n = 1000;
        try (HaloEngine engine = HaloEngine.open(dir, smallFiles())) {
            for (int i = 0; i < n; i++) {
                engine.put(key(i), value(i));
            }
            for (int i = 0; i < n; i += 2) {
                engine.delete(key(i));
            }
        }

        try (HaloEngine engine = HaloEngine.open(dir, smallFiles())) {
            assertEquals(n / 2, engine.size());
            for (int i = 0; i < n; i++) {
                if (i % 2 == 0) {
                    assertNull(engine.get(key(i)), "deleted key " + i);
                } else {
                    assertArrayEquals(value(i), engine.get(key(i)), "key " + i);
                }
            }
        }
    }

    @Test
    void latestVersionWinsAfterReopen(@TempDir final Path dir) throws Exception {
        try (HaloEngine engine = HaloEngine.open(dir, smallFiles())) {
            for (int v = 0; v < 5; v++) {
                engine.put(key(1), ("v" + v).getBytes(StandardCharsets.UTF_8));
            }
        }
        try (HaloEngine engine = HaloEngine.open(dir, smallFiles())) {
            assertArrayEquals("v4".getBytes(StandardCharsets.UTF_8), engine.get(key(1)));
            assertEquals(1, engine.size());
        }
    }

    @Test
    void dirtyShutdownIsDetectedAndRecovered(@TempDir final Path dir) throws Exception {
        final int n = 500;
        final HaloEngine crashed = HaloEngine.open(dir, smallFiles());
        for (int i = 0; i < n; i++) {
            crashed.put(key(i), value(i));
        }
        // Simulate a crash: do not close; data is already in the page cache.
        assertTrue(MetaFile.read(dir.resolve("META")).open, "META should be marked open (dirty)");

        try (HaloEngine engine = HaloEngine.open(dir, smallFiles())) {
            assertEquals(n, engine.size());
            for (int i = 0; i < n; i++) {
                assertArrayEquals(value(i), engine.get(key(i)), "recovered key " + i);
            }
        }
        crashed.close();
    }
}
