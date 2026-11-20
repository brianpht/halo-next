/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.benchmark;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;

/**
 * Thin HdrHistogram wrapper for latency capture. Tail percentiles are the contract,
 * so every read path reports p50 / p90 / p99 / p99.9 / p99.99 / max (microseconds).
 */
public final class LatencyStats {

    private static final long MAX_TRACKED_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final Histogram histogram = new Histogram(MAX_TRACKED_NANOS, 3);

    /** Records one latency sample in nanoseconds, clamped to the trackable range. */
    public void record(final long nanos) {
        histogram.recordValue(Math.max(0, Math.min(nanos, MAX_TRACKED_NANOS)));
    }

    /** Merges another recorder (per-thread histogram) into this aggregate. */
    public synchronized void add(final LatencyStats other) {
        histogram.add(other.histogram);
    }

    public long count() {
        return histogram.getTotalCount();
    }

    /** Appends latency metrics (microseconds) into the result map. */
    public void report(final Map<String, Object> out) {
        out.put("latency_count", histogram.getTotalCount());
        out.put("latency_mean_us", us(histogram.getMean()));
        out.put("latency_p50_us", us(histogram.getValueAtPercentile(50.0)));
        out.put("latency_p90_us", us(histogram.getValueAtPercentile(90.0)));
        out.put("latency_p99_us", us(histogram.getValueAtPercentile(99.0)));
        out.put("latency_p99_9_us", us(histogram.getValueAtPercentile(99.9)));
        out.put("latency_p99_99_us", us(histogram.getValueAtPercentile(99.99)));
        out.put("latency_max_us", us(histogram.getMaxValue()));
    }

    private static double us(final double nanos) {
        return Math.round(nanos / 10.0) / 100.0;
    }
}
