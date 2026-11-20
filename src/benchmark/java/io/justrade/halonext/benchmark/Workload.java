/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.benchmark;

/**
 * Macro workloads ported one-to-one from the original HaloDB BenchmarkTool, plus
 * COMPACTION and SOAK for the halo-next Phase 5 scope.
 */
public enum Workload {
    FILL_SEQUENCE,
    FILL_RANDOM,
    READ_RANDOM,
    RANDOM_UPDATE,
    READ_AND_UPDATE,
    COMPACTION,
    SOAK;

    public static Workload parse(final String name) {
        return Workload.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
