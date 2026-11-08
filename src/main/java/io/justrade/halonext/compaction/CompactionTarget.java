/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.compaction;

import java.io.IOException;

/**
 * The engine-facing contract the compaction agent drives. Keeps the agent free of
 * engine internals: it only polls file ids, sizes them for rate limiting, and asks
 * the engine to compact them.
 */
public interface CompactionTarget {

    /** Returns the next file id queued for compaction, or -1 if none are pending. */
    int pollFileId();

    /** Size in bytes of the file, used to pace the rate limiter. */
    long fileSizeBytes(int fileId);

    /** Rewrites the live records of the file out of place and retires the old file. */
    void compact(int fileId) throws IOException;
}
