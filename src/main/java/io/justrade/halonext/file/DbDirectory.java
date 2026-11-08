/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import org.agrona.collections.IntArrayList;

/**
 * Manages a database directory: creation, file discovery by id, and directory
 * fsync. File ids are returned sorted ascending.
 */
public final class DbDirectory {

    private final Path path;

    private DbDirectory(final Path path) {
        this.path = path;
    }

    /** Opens the directory, creating it (and parents) if it does not exist. */
    public static DbDirectory open(final Path path) throws IOException {
        Files.createDirectories(path);
        return new DbDirectory(path);
    }

    public Path path() {
        return path;
    }

    public Path metaFile() {
        return path.resolve(FileNames.META_FILE);
    }

    public boolean metaFileExists() {
        return Files.exists(metaFile());
    }

    public int[] listDataFileIds() throws IOException {
        return listIds(FileNames.DATA_FILE_PATTERN);
    }

    public int[] listIndexFileIds() throws IOException {
        return listIds(FileNames.INDEX_FILE_PATTERN);
    }

    public int[] listTombstoneFileIds() throws IOException {
        return listIds(FileNames.TOMBSTONE_FILE_PATTERN);
    }

    /** fsyncs the directory inode so file creation / deletion is durable (best effort). */
    public void sync() {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (final IOException ignored) {
            // Some platforms do not support directory fsync; ignore.
        }
    }

    private int[] listIds(final Pattern pattern) throws IOException {
        final IntArrayList ids = new IntArrayList();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (final Path entry : stream) {
                final int id = FileNames.parseFileId(entry.getFileName().toString(), pattern);
                if (id >= 0) {
                    ids.addInt(id);
                }
            }
        }
        final int[] result = new int[ids.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ids.getInt(i);
        }
        java.util.Arrays.sort(result);
        return result;
    }
}
