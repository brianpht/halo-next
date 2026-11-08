/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justrade.halonext.compaction.CompactionAgent;
import io.justrade.halonext.compaction.RateLimiter;
import io.justrade.halonext.config.HaloDBOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactionIT {

    private static byte[] key(final int i) {
        return ("key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(final int i, final int round) {
        return ("value-" + i + "-round-" + round).getBytes(StandardCharsets.UTF_8);
    }

    private static HaloDBOptions options(final boolean backgroundCompaction) {
        return new HaloDBOptions()
                .setMaxFileSize(512)
                .setMaxTombstoneFileSize(256)
                .setNumberOfRecords(1024)
                .setCompactionThresholdPerFile(0.5)
                .setCompactionEnabled(backgroundCompaction);
    }

    private static int countDataFiles(final Path dir) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.data")) {
            for (final Path ignored : stream) {
                count++;
            }
        }
        return count;
    }

    @Test
    void manualCompactionReclaimsSpaceWithoutDataLoss(@TempDir final Path dir) throws Exception {
        final int n = 200;
        final int rounds = 5;
        try (HaloEngine engine = HaloEngine.open(dir, options(false))) {
            for (int round = 0; round <= rounds; round++) {
                for (int i = 0; i < n; i++) {
                    engine.put(key(i), value(i, round));
                }
            }
            final int filesBefore = countDataFiles(dir);

            final CompactionAgent agent = new CompactionAgent(engine, new RateLimiter(0));
            while (agent.doWork() > 0) {
                // drain the queue
            }

            final int filesAfter = countDataFiles(dir);
            assertTrue(filesAfter < filesBefore, "expected fewer data files: " + filesAfter + " < " + filesBefore);
            assertTrue(engine.isCompactionComplete());

            assertEquals(n, engine.size());
            for (int i = 0; i < n; i++) {
                assertArrayEquals(value(i, rounds), engine.get(key(i)), "key " + i);
            }
        }

        try (HaloEngine engine = HaloEngine.open(dir, options(false))) {
            assertEquals(n, engine.size());
            for (int i = 0; i < n; i++) {
                assertArrayEquals(value(i, rounds), engine.get(key(i)), "reopened key " + i);
            }
        }
    }

    @Test
    void backgroundCompactionReclaimsSpaceWithoutDataLoss(@TempDir final Path dir) throws Exception {
        final int n = 200;
        final int rounds = 6;
        try (HaloEngine engine = HaloEngine.open(dir, options(true))) {
            for (int round = 0; round <= rounds; round++) {
                for (int i = 0; i < n; i++) {
                    engine.put(key(i), value(i, round));
                }
            }

            final long deadline = System.nanoTime() + 20_000_000_000L;
            while (!engine.isCompactionComplete() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(engine.isCompactionComplete(), "compaction did not drain in time");

            assertEquals(n, engine.size());
            for (int i = 0; i < n; i++) {
                assertArrayEquals(value(i, rounds), engine.get(key(i)), "key " + i);
            }
        }

        try (HaloEngine engine = HaloEngine.open(dir, options(true))) {
            assertEquals(n, engine.size());
            for (int i = 0; i < n; i++) {
                assertArrayEquals(value(i, rounds), engine.get(key(i)), "reopened key " + i);
            }
        }
    }

    @Test
    void compactionAfterDeletesDropsData(@TempDir final Path dir) throws Exception {
        final int n = 300;
        try (HaloEngine engine = HaloEngine.open(dir, options(false))) {
            for (int i = 0; i < n; i++) {
                engine.put(key(i), value(i, 0));
            }
            for (int i = 0; i < n; i += 2) {
                engine.delete(key(i));
            }

            final CompactionAgent agent = new CompactionAgent(engine, new RateLimiter(0));
            while (agent.doWork() > 0) {
                // drain
            }

            assertEquals(n / 2, engine.size());
            for (int i = 0; i < n; i++) {
                if (i % 2 == 0) {
                    assertNull(engine.get(key(i)), "deleted key " + i);
                } else {
                    assertArrayEquals(value(i, 0), engine.get(key(i)), "key " + i);
                }
            }
        }
    }
}
