/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.compaction;

import java.util.function.BooleanSupplier;

/**
 * Simple token-bucket rate limiter in bytes per second, replacing the Guava
 * RateLimiter used by the original HaloDB. Keeps compaction from saturating IO
 * bandwidth. A non-positive rate means unlimited.
 */
public final class RateLimiter {

    /**
     * The throttle sleep is split into slices of at most this many milliseconds so an
     * abort signal (for example a pending compaction pause) is observed promptly
     * instead of after the whole deficit has been slept off.
     */
    private static final long MAX_SLEEP_SLICE_MILLIS = 50;

    private static final BooleanSupplier NEVER_ABORT = () -> false;

    private final double bytesPerSecond;
    private double allowance;
    private long lastNanos;

    public RateLimiter(final long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
        this.allowance = Math.max(bytesPerSecond, 0);
        this.lastNanos = System.nanoTime();
    }

    /** Blocks as needed so the long-run throughput does not exceed the configured rate. */
    public void acquire(final long bytes) {
        acquire(bytes, NEVER_ABORT);
    }

    /**
     * Blocks as needed so the long-run throughput does not exceed the configured rate,
     * but stops waiting early once {@code abort} reports true. Aborting skips the
     * remaining throttle for this one acquisition so a quiesce request (pause, close)
     * is not delayed by a long rate-limit sleep.
     */
    public synchronized void acquire(final long bytes, final BooleanSupplier abort) {
        if (bytesPerSecond <= 0 || bytes <= 0) {
            return;
        }
        final long now = System.nanoTime();
        allowance += (now - lastNanos) / 1.0e9 * bytesPerSecond;
        lastNanos = now;
        if (allowance > bytesPerSecond) {
            allowance = bytesPerSecond;
        }
        if (allowance >= bytes) {
            allowance -= bytes;
            return;
        }
        final double deficit = bytes - allowance;
        allowance = 0;
        long remainingMillis = (long) (deficit / bytesPerSecond * 1000.0);
        while (remainingMillis > 0) {
            if (abort.getAsBoolean()) {
                return;
            }
            final long slice = Math.min(remainingMillis, MAX_SLEEP_SLICE_MILLIS);
            try {
                Thread.sleep(slice);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            remainingMillis -= slice;
        }
    }
}
