/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justrade.halonext.config.HaloDBOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic replay: a recorded operation stream applied to the engine must
 * reproduce exactly the same live state, and reopening must rebuild an identical
 * key-value mapping from the persisted index.
 */
class ReplayIT {

    private static final long SEED = 20_261_108L;
    private static final int OPERATIONS = 20_000;
    private static final int KEY_SPACE = 800;

    private static byte[] key(final int i) {
        return ("key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static String str(final byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static HaloDBOptions options() {
        return new HaloDBOptions()
                .setMaxFileSize(1024)
                .setMaxTombstoneFileSize(512)
                .setNumberOfRecords(4096)
                .setCompactionThresholdPerFile(0.5);
    }

    @Test
    void recordedStreamRebuildsIdenticalState(@TempDir final Path dir) throws Exception {
        final Random random = new Random(SEED);
        final Map<String, String> oracle = new HashMap<>();

        try (HaloDB db = HaloDB.open(dir, options())) {
            for (int op = 0; op < OPERATIONS; op++) {
                final int k = random.nextInt(KEY_SPACE);
                if (random.nextInt(100) < 75) {
                    final byte[] value = ("v" + op).getBytes(StandardCharsets.UTF_8);
                    db.put(key(k), value);
                    oracle.put(str(key(k)), str(value));
                } else {
                    db.delete(key(k));
                    oracle.remove(str(key(k)));
                }
            }
            assertState(db, oracle);
        }

        try (HaloDB db = HaloDB.open(dir, options())) {
            assertState(db, oracle);
        }
    }

    private static void assertState(final HaloDB db, final Map<String, String> oracle) throws Exception {
        assertEquals(oracle.size(), db.size(), "size mismatch");
        for (final Map.Entry<String, String> entry : oracle.entrySet()) {
            assertArrayEquals(
                    entry.getValue().getBytes(StandardCharsets.UTF_8),
                    db.get(entry.getKey().getBytes(StandardCharsets.UTF_8)),
                    "value mismatch for " + entry.getKey());
        }
        final Set<String> keys = new HashSet<>();
        try (HaloDBKeyIterator it = db.newKeyIterator()) {
            while (it.hasNext()) {
                keys.add(str(it.next()));
            }
        }
        assertEquals(oracle.keySet(), keys, "live key set mismatch");
    }
}
