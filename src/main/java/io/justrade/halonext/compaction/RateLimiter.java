/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.compaction;

/**
 * Simple token-bucket rate limiter in bytes per second, replacing the Guava
 * RateLimiter used by the original HaloDB. Keeps compaction from saturating IO
 * bandwidth. A non-positive rate means unlimited.
 */
public final class RateLimiter {

    private final double bytesPerSecond;
    private double allowance;
    private long lastNanos;

    public RateLimiter(final long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
        this.allowance = Math.max(bytesPerSecond, 0);
        this.lastNanos = System.nanoTime();
    }

    /** Blocks as needed so the long-run throughput does not exceed the configured rate. */
    public synchronized void acquire(final long bytes) {
        if (bytesPerSecond <= 0 || bytes <= 0) {
            return;
        }
        final long now = System.nanoTime();
        allowance += (now - lastNanos) / 1.0e9 * bytesPerSecond;
        lastNanos = now;
        if (allowance > bytesPerSecond) {
            allowance = bytesPerSecond;
        }
        if (allowance < bytes) {
            final double deficit = bytes - allowance;
            final long sleepMillis = (long) (deficit / bytesPerSecond * 1000.0);
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            allowance = 0;
        } else {
            allowance -= bytes;
        }
    }
}
