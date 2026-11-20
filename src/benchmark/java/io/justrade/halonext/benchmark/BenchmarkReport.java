/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Minimal reporter: prints a result map to stdout and writes a JSON and a markdown
 * table under {@code build/results/benchmark/}. No external JSON dependency; the map
 * holds only numbers, strings, and booleans.
 */
public final class BenchmarkReport {

    private BenchmarkReport() {}

    public static void emit(final Path outputDir, final String name, final Map<String, Object> metrics)
            throws IOException {
        System.out.println();
        System.out.println("=== " + name + " ===");
        metrics.forEach((k, v) -> System.out.println("  " + k + " = " + v));

        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve(name + ".json"), toJson(metrics), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve(name + ".md"), toMarkdown(name, metrics), StandardCharsets.UTF_8);
        System.out.println("Report written to " + outputDir.resolve(name + ".json"));
    }

    private static String toJson(final Map<String, Object> metrics) {
        final StringBuilder b = new StringBuilder("{\n");
        int i = 0;
        for (final Map.Entry<String, Object> e : metrics.entrySet()) {
            b.append("  \"").append(e.getKey()).append("\": ").append(jsonValue(e.getValue()));
            if (++i < metrics.size()) {
                b.append(',');
            }
            b.append('\n');
        }
        return b.append("}\n").toString();
    }

    private static String jsonValue(final Object v) {
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return "\"" + String.valueOf(v).replace("\"", "\\\"") + "\"";
    }

    private static String toMarkdown(final String name, final Map<String, Object> metrics) {
        final StringBuilder b = new StringBuilder("# ").append(name).append("\n\n");
        b.append("| Metric | Value |\n|--------|-------|\n");
        metrics.forEach(
                (k, v) -> b.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        return b.toString();
    }
}
