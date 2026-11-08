/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.compaction;

import org.agrona.concurrent.Agent;

/**
 * Background compaction loop as an Agrona {@link Agent}: one file id per unit of
 * work, rate-limited by the file size. Run on a dedicated thread via an
 * {@code AgentRunner} with a {@code BackoffIdleStrategy}.
 */
public final class CompactionAgent implements Agent {

    private final CompactionTarget target;
    private final RateLimiter rateLimiter;

    public CompactionAgent(final CompactionTarget target, final RateLimiter rateLimiter) {
        this.target = target;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public int doWork() throws Exception {
        final int fileId = target.beginCompaction();
        if (fileId < 0) {
            return 0;
        }
        try {
            rateLimiter.acquire(target.fileSizeBytes(fileId), target::isPausePending);
            target.compact(fileId);
        } finally {
            target.endCompaction();
        }
        return 1;
    }

    @Override
    public String roleName() {
        return "halo-compaction";
    }
}
