/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.compaction;

import java.io.IOException;

/**
 * The engine-facing contract the compaction agent drives. Keeps the agent free of
 * engine internals: it only begins a work unit, sizes the file for rate limiting,
 * compacts it, and ends the work unit.
 *
 * <p>A compaction "work unit" spans {@link #beginCompaction()} through
 * {@link #endCompaction()} and covers the rate-limit wait in between. The engine
 * uses this bracket to quiesce compaction for a consistent snapshot: a pause that
 * arrives mid-unit waits for {@code endCompaction}, closing the race where the old
 * busy flag was only set after the (possibly long) rate-limit sleep.
 */
public interface CompactionTarget {

    /**
     * Starts the next compaction work unit and returns its file id, or -1 when the
     * queue is empty or compaction is paused. A non-negative return marks a unit as
     * in flight and MUST be paired with exactly one {@link #endCompaction()} call.
     */
    int beginCompaction();

    /** Size in bytes of the file, used to pace the rate limiter. */
    long fileSizeBytes(int fileId);

    /** Rewrites the live records of the file out of place and retires the old file. */
    void compact(int fileId) throws IOException;

    /** Ends the current work unit, releasing any pause waiting for compaction to quiesce. */
    void endCompaction();

    /** True when a pause has been requested; used to abort the rate-limit wait early. */
    boolean isPausePending();
}
