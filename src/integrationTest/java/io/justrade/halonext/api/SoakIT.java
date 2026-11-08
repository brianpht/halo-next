/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justrade.halonext.config.HaloDBOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Long-running mixed workload with background compaction. Asserts integrity under
 * continuous churn, that reads stay correct while compaction runs, and that
 * compaction keeps the data-file count bounded (space reclaimed).
 */
class SoakIT {

    private static final long SEED = 424_242L;
    private static final int OPERATIONS = 50_000;
    private static final int KEY_SPACE = 2_000;

    private static byte[] key(final int i) {
        return ("key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static String str(final byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static HaloDBOptions options() {
        return new HaloDBOptions()
                .setMaxFileSize(2048)
                .setMaxTombstoneFileSize(1024)
                .setNumberOfRecords(8192)
                .setCompactionThresholdPerFile(0.5)
                .setCompactionEnabled(true);
    }

    @Test
    void mixedWorkloadStaysConsistent(@TempDir final Path dir) throws Exception {
        final Random random = new Random(SEED);
        final Map<String, String> oracle = new HashMap<>();

        try (HaloDB db = HaloDB.open(dir, options())) {
            for (int op = 0; op < OPERATIONS; op++) {
                final int k = random.nextInt(KEY_SPACE);
                final int roll = random.nextInt(100);
                if (roll < 70) {
                    final byte[] value = ("v" + op).getBytes(StandardCharsets.UTF_8);
                    db.put(key(k), value);
                    oracle.put(str(key(k)), str(value));
                } else if (roll < 90) {
                    final byte[] got = db.get(key(k));
                    final String expected = oracle.get(str(key(k)));
                    if (expected == null) {
                        assertNull(got, "expected absent key " + k);
                    } else {
                        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), got, "key " + k);
                    }
                } else {
                    db.delete(key(k));
                    oracle.remove(str(key(k)));
                }
            }

            final long deadline = System.nanoTime() + 30_000_000_000L;
            while (!db.isCompactionComplete() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(db.isCompactionComplete(), "compaction did not drain");

            assertEquals(oracle.size(), db.size());
            for (final Map.Entry<String, String> entry : oracle.entrySet()) {
                assertArrayEquals(
                        entry.getValue().getBytes(StandardCharsets.UTF_8),
                        db.get(entry.getKey().getBytes(StandardCharsets.UTF_8)));
            }

            // Compaction should keep the live data within a small multiple of the
            // minimum number of files needed, proving space is reclaimed.
            final int liveFiles = db.stats().getNumberOfDataFiles();
            assertTrue(liveFiles < 256, "data files not bounded by compaction: " + liveFiles);
        }

        try (HaloDB db = HaloDB.open(dir, options())) {
            assertEquals(oracle.size(), db.size());
            for (final Map.Entry<String, String> entry : oracle.entrySet()) {
                assertArrayEquals(
                        entry.getValue().getBytes(StandardCharsets.UTF_8),
                        db.get(entry.getKey().getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
