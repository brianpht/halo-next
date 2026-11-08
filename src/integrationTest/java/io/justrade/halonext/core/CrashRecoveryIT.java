/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justrade.halonext.compaction.CompactionAgent;
import io.justrade.halonext.compaction.RateLimiter;
import io.justrade.halonext.config.HaloDBOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash-injection: abandon the engine (no clean close) at various points and
 * assert recovery reproduces the expected state. Visibility relies on the OS page
 * cache, which models a process crash (not a power loss).
 */
class CrashRecoveryIT {

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
                .setCompactionThresholdPerFile(0.5)
                .setCompactionEnabled(false);
    }

    private static void assertMatches(final HaloEngine engine, final Map<String, String> oracle) throws Exception {
        assertEquals(oracle.size(), engine.size());
        for (final Map.Entry<String, String> entry : oracle.entrySet()) {
            assertArrayEquals(
                    entry.getValue().getBytes(StandardCharsets.UTF_8),
                    engine.get(entry.getKey().getBytes(StandardCharsets.UTF_8)),
                    "key " + entry.getKey());
        }
    }

    @Test
    void repeatedCrashCyclesPreserveData(@TempDir final Path dir) throws Exception {
        final Random random = new Random(7L);
        final Map<String, String> oracle = new HashMap<>();

        for (int cycle = 0; cycle < 4; cycle++) {
            final HaloEngine engine = HaloEngine.open(dir, options());
            for (int op = 0; op < 2000; op++) {
                final int k = random.nextInt(400);
                if (random.nextInt(100) < 75) {
                    final byte[] value = ("c" + cycle + "-" + op).getBytes(StandardCharsets.UTF_8);
                    engine.put(key(k), value);
                    oracle.put(str(key(k)), str(value));
                } else {
                    engine.delete(key(k));
                    oracle.remove(str(key(k)));
                }
            }
            // Crash: abandon without close.
        }

        try (HaloEngine engine = HaloEngine.open(dir, options())) {
            assertMatches(engine, oracle);
        }
    }

    @Test
    void crashDuringCompactionRecovers(@TempDir final Path dir) throws Exception {
        final Map<String, String> oracle = new HashMap<>();
        final int n = 400;

        final HaloEngine engine = HaloEngine.open(dir, options());
        for (int i = 0; i < n; i++) {
            engine.put(key(i), ("v0-" + i).getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < n; i++) {
            final byte[] value = ("v1-" + i).getBytes(StandardCharsets.UTF_8);
            engine.put(key(i), value);
            oracle.put(str(key(i)), str(value));
        }

        // Partially compact: migrate some stale files, then crash mid-stream.
        final CompactionAgent agent = new CompactionAgent(engine, new RateLimiter(0));
        for (int i = 0; i < 3; i++) {
            agent.doWork();
        }
        // Crash: abandon without close.

        try (HaloEngine reopened = HaloEngine.open(dir, options())) {
            assertEquals(n, reopened.size());
            for (int i = 0; i < n; i++) {
                assertArrayEquals(("v1-" + i).getBytes(StandardCharsets.UTF_8), reopened.get(key(i)), "key " + i);
            }
            assertNull(reopened.get(key(n + 1)));
        }
    }
}
