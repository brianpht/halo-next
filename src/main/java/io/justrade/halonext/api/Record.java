/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.api;

/** An immutable key-value pair yielded by {@link HaloDBIterator}. */
public final class Record {

    private final byte[] key;
    private final byte[] value;

    Record(final byte[] key, final byte[] value) {
        this.key = key;
        this.value = value;
    }

    public byte[] getKey() {
        return key;
    }

    public byte[] getValue() {
        return value;
    }
}
