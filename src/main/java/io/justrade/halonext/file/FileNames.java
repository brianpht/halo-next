/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File naming conventions for a halo-next database directory. File ids are plain
 * decimal integers with no padding, matching the original HaloDB layout.
 */
public final class FileNames {

    public static final String DATA_EXTENSION = ".data";
    public static final String COMPACTED_DATA_EXTENSION = ".datac";
    public static final String INDEX_EXTENSION = ".index";
    public static final String TOMBSTONE_EXTENSION = ".tombstone";
    public static final String REPAIR_EXTENSION = ".repair";
    public static final String META_FILE = "META";

    public static final Pattern DATA_FILE_PATTERN = Pattern.compile("([0-9]+)\\.data(c)?");
    public static final Pattern INDEX_FILE_PATTERN = Pattern.compile("([0-9]+)\\.index");
    public static final Pattern TOMBSTONE_FILE_PATTERN = Pattern.compile("([0-9]+)\\.tombstone");

    private FileNames() {}

    public static String dataFile(final int fileId) {
        return fileId + DATA_EXTENSION;
    }

    public static String compactedDataFile(final int fileId) {
        return fileId + COMPACTED_DATA_EXTENSION;
    }

    public static String indexFile(final int fileId) {
        return fileId + INDEX_EXTENSION;
    }

    public static String tombstoneFile(final int fileId) {
        return fileId + TOMBSTONE_EXTENSION;
    }

    /** Returns the file id parsed from a name, or -1 if it does not match the pattern. */
    public static int parseFileId(final String name, final Pattern pattern) {
        final Matcher matcher = pattern.matcher(name);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }
}
