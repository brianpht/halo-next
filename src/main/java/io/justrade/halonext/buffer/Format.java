/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.buffer;

import java.nio.ByteOrder;

/**
 * Shared constants for the halo-next Format v1 on-disk layout. See
 * docs/decisions/0002-on-disk-format.md.
 */
public final class Format {

    /** All multi-byte fields are little-endian (native x86 / ARM order, Agrona default). */
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    /** Size of the leading CRC32C checksum field present in every structure. */
    public static final int CHECKSUM_SIZE = 4;

    /** Maximum key size in bytes (key size is encoded as a single unsigned byte). */
    public static final int MAX_KEY_SIZE = 255;

    /** Format version written into the data record header. */
    public static final int DATA_FILE_VERSION = 1;

    /** Format version written into the index entry header. */
    public static final int INDEX_FILE_VERSION = 1;

    /** Format version written into the tombstone entry header. */
    public static final int TOMBSTONE_FILE_VERSION = 1;

    /** Format version written into the DB metadata (META) block. */
    public static final int META_VERSION = 1;

    private Format() {}
}
