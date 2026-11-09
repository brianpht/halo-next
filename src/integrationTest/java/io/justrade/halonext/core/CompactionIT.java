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
    void snapshotDuringRateLimitedCompactionStaysConsistent(@TempDir final Path dir) throws Exception {
        final int n = 300;
        final int rounds = 8;
        // A low job rate forces the rate limiter to sleep, so compaction is in flight (and
        // mid-throttle) when snapshots are taken - exactly the window the old pause race lost.
        final HaloDBOptions opts = options(true).setCompactionJobRate(8 * 1024);
        try (HaloEngine engine = HaloEngine.open(dir, opts)) {
            for (int round = 0; round <= rounds; round++) {
                for (int i = 0; i < n; i++) {
                    engine.put(key(i), value(i, round));
                }
            }

            // Overwrites leave many fully stale files queued for rate-limited compaction.
            assertFalse(engine.isCompactionComplete(), "expected pending compaction right after writes");

            int snapshotsWhileCompacting = 0;
            final long deadline = System.nanoTime() + 30_000_000_000L;
            while (!engine.isCompactionComplete() && System.nanoTime() < deadline) {
                assertTrue(engine.snapshot(), "snapshot during active compaction");
                assertSnapshotConsistent(engine.getSnapshotDirectory(), n, rounds);
                snapshotsWhileCompacting++;
            }
            assertTrue(snapshotsWhileCompacting > 0, "no snapshot ran while compaction was active");

            // And once compaction has drained, a final snapshot is still consistent.
            assertTrue(engine.snapshot());
            assertSnapshotConsistent(engine.getSnapshotDirectory(), n, rounds);

            assertEquals(n, engine.size());
            for (int i = 0; i < n; i++) {
                assertArrayEquals(value(i, rounds), engine.get(key(i)), "live key " + i);
            }
        }
    }

    /** Reopens the hard-linked snapshot as a fresh engine and asserts every live key resolves. */
    private static void assertSnapshotConsistent(final Path snapshotDir, final int n, final int rounds)
            throws IOException {
        final HaloDBOptions readOptions = new HaloDBOptions()
                .setMaxFileSize(512)
                .setMaxTombstoneFileSize(256)
                .setNumberOfRecords(1024)
                .setCompactionEnabled(false);
        try (HaloEngine snapshot = HaloEngine.open(snapshotDir, readOptions)) {
            assertEquals(n, snapshot.size(), "snapshot live count");
            for (int i = 0; i < n; i++) {
                assertArrayEquals(value(i, rounds), snapshot.get(key(i)), "snapshot key " + i);
            }
        }
    }

    @Test
    void nestedPauseHoldersRequireMatchingResumes(@TempDir final Path dir) throws Exception {
        try (HaloEngine engine = HaloEngine.open(dir, options(false))) {
            engine.pauseCompaction();
            engine.pauseCompaction();
            assertTrue(engine.isPausePending(), "two holders: pause pending");

            engine.resumeCompaction();
            assertTrue(engine.isPausePending(), "one holder left: pause must stay pending");

            engine.resumeCompaction();
            assertFalse(engine.isPausePending(), "last holder resumed: pause cleared");

            // An unbalanced extra resume must not underflow the count or resume early.
            engine.resumeCompaction();
            assertFalse(engine.isPausePending());
            engine.pauseCompaction();
            assertTrue(engine.isPausePending(), "a fresh pause still takes effect after an extra resume");
            engine.resumeCompaction();
            assertFalse(engine.isPausePending());
        }
    }

    @Test
    void snapshotDoesNotResumeAPauseHeldByAnotherHolder(@TempDir final Path dir) throws Exception {
        final int n = 120;
        final int rounds = 4;
        try (HaloEngine engine = HaloEngine.open(dir, options(false))) {
            for (int round = 0; round <= rounds; round++) {
                for (int i = 0; i < n; i++) {
                    engine.put(key(i), value(i, round));
                }
            }
            // Overwrites leave fully stale files queued, so beginCompaction would poll one
            // unless a pause blocks it - this makes the -1 assertion below meaningful.
            assertFalse(engine.isCompactionComplete(), "expected queued files for compaction");

            // Holder A (modelling a live iterator) pauses compaction for its whole lifetime.
            engine.pauseCompaction();
            assertTrue(engine.isPausePending());

            // Holder B (snapshot) nests its own pause/resume inside A's pause window.
            assertTrue(engine.snapshot());

            // With the old binary flag, B's resume cleared A's pause here, letting compaction
            // retire a file under A. With refcounting, A's pause survives.
            assertTrue(engine.isPausePending(), "iterator pause must survive the snapshot resume");
            assertEquals(-1, engine.beginCompaction(), "no new compaction unit may start under holder A");

            // Holder A releases: compaction resumes and the queued work is now pollable.
            engine.resumeCompaction();
            assertFalse(engine.isPausePending(), "pause released after the last holder resumes");
            final int fileId = engine.beginCompaction();
            assertTrue(fileId >= 0, "queue had work; the earlier -1 was the pause, not an empty queue");
            engine.endCompaction();
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
