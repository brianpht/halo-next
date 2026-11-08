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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HaloDBApiIT {

    private static byte[] key(final int i) {
        return ("key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(final int i) {
        return ("value-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static String str(final byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static HaloDBOptions options() {
        return new HaloDBOptions()
                .setMaxFileSize(512)
                .setMaxTombstoneFileSize(256)
                .setNumberOfRecords(1024);
    }

    @Test
    void openPutGetDeleteReopen(@TempDir final Path dir) throws Exception {
        try (HaloDB db = HaloDB.open(dir, options())) {
            assertNull(db.get(key(1)));
            assertTrue(db.put(key(1), value(1)));
            assertArrayEquals(value(1), db.get(key(1)));
            db.delete(key(1));
            assertNull(db.get(key(1)));
            assertEquals(0, db.size());
        }

        try (HaloDB db = HaloDB.open(dir.toString(), options())) {
            assertTrue(db.put(key(2), value(2)));
        }
        try (HaloDB db = HaloDB.open(dir.toFile(), options())) {
            assertArrayEquals(value(2), db.get(key(2)));
        }
    }

    @Test
    void iteratorsYieldLiveRecords(@TempDir final Path dir) throws Exception {
        final int n = 500;
        try (HaloDB db = HaloDB.open(dir, options())) {
            for (int i = 0; i < n; i++) {
                db.put(key(i), value(i));
            }
            for (int i = 0; i < n; i += 3) {
                db.delete(key(i));
            }

            final Set<String> expected = new HashSet<>();
            for (int i = 0; i < n; i++) {
                if (i % 3 != 0) {
                    expected.add(str(key(i)));
                }
            }

            final Set<String> keys = new HashSet<>();
            try (HaloDBKeyIterator it = db.newKeyIterator()) {
                while (it.hasNext()) {
                    keys.add(str(it.next()));
                }
            }
            assertEquals(expected, keys);

            final Map<String, String> pairs = new HashMap<>();
            try (HaloDBIterator it = db.newIterator()) {
                while (it.hasNext()) {
                    final Record record = it.next();
                    pairs.put(str(record.getKey()), str(record.getValue()));
                }
            }
            assertEquals(expected.size(), pairs.size());
            for (int i = 0; i < n; i++) {
                if (i % 3 != 0) {
                    assertEquals(str(value(i)), pairs.get(str(key(i))));
                }
            }
        }
    }

    @Test
    void statsReflectState(@TempDir final Path dir) throws Exception {
        try (HaloDB db = HaloDB.open(dir, options())) {
            for (int i = 0; i < 100; i++) {
                db.put(key(i), value(i));
            }
            for (int i = 0; i < 100; i++) {
                db.get(key(i));
            }
            db.delete(key(0));

            final HaloDBStats stats = db.stats();
            assertEquals(99, stats.getSize());
            assertEquals(100, stats.getNumberOfPuts());
            assertEquals(100, stats.getNumberOfGets());
            assertEquals(1, stats.getNumberOfDeletes());
            assertTrue(stats.getNumberOfDataFiles() >= 1);

            db.resetStats();
            assertEquals(0, db.stats().getNumberOfPuts());
        }
    }

    @Test
    void snapshotCapturesFiles(@TempDir final Path dir) throws Exception {
        try (HaloDB db = HaloDB.open(dir, options())) {
            for (int i = 0; i < 50; i++) {
                db.put(key(i), value(i));
            }
            assertTrue(db.snapshot());
            final Path snapshotDir = db.getSnapshotDirectory().toPath();
            assertTrue(Files.isDirectory(snapshotDir));
            try (var stream = Files.newDirectoryStream(snapshotDir, "*.data")) {
                assertTrue(stream.iterator().hasNext(), "snapshot should contain data files");
            }
            assertTrue(db.clearSnapshot());
            assertTrue(!Files.exists(snapshotDir));
        }
    }

    @Test
    void iteratorReturnsPreexistingKeysDuringConcurrentWrites(@TempDir final Path dir) throws Exception {
        final int base = 300;
        try (HaloDB db = HaloDB.open(dir, options())) {
            for (int i = 0; i < base; i++) {
                db.put(key(i), value(i));
            }

            final Thread writer = new Thread(() -> {
                try {
                    for (int i = base; i < base + 300; i++) {
                        db.put(key(i), value(i));
                    }
                } catch (final HaloDBException ex) {
                    throw new RuntimeException(ex);
                }
            });

            final Set<String> seen = new HashSet<>();
            try (HaloDBKeyIterator it = db.newKeyIterator()) {
                writer.start();
                while (it.hasNext()) {
                    seen.add(str(it.next()));
                }
            }
            writer.join();

            for (int i = 0; i < base; i++) {
                assertTrue(seen.contains(str(key(i))), "missing pre-existing key " + i);
            }
        }
    }
}
