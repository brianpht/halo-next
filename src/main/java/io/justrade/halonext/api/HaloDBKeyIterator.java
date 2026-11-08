/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.api;

import io.justrade.halonext.core.HaloEngine;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Forward iterator over the keys of all live records. Weakly consistent: reflects
 * the set of live keys at creation time. Close it (or exhaust it) to resume
 * compaction.
 */
public final class HaloDBKeyIterator implements Iterator<byte[]>, Closeable {

    private final RecordScanner scanner;
    private boolean hasNext;

    HaloDBKeyIterator(final HaloEngine engine) {
        this.scanner = new RecordScanner(engine);
        this.hasNext = advance();
    }

    private boolean advance() {
        try {
            return scanner.advance(false);
        } catch (final IOException ex) {
            scanner.close();
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public boolean hasNext() {
        return hasNext;
    }

    @Override
    public byte[] next() {
        if (!hasNext) {
            throw new NoSuchElementException();
        }
        final byte[] key = scanner.key();
        hasNext = advance();
        return key;
    }

    @Override
    public void close() {
        scanner.close();
    }
}
