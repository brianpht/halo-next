/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.core;

import io.justrade.halonext.buffer.DbMetaDataFlyweight;
import io.justrade.halonext.buffer.Format;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Reads and writes the fixed-size META block that gates clean-vs-dirty shutdown
 * detection. {@code open == true} on disk at startup means the previous run did
 * not close cleanly.
 */
final class MetaFile {

    private MetaFile() {}

    /** Snapshot of the META block; {@link #valid} is false when the file is missing or corrupt. */
    static final class Meta {
        final boolean valid;
        final boolean open;
        final boolean ioError;
        final long sequenceNumber;
        final int maxFileSize;

        Meta(
                final boolean valid,
                final boolean open,
                final boolean ioError,
                final long sequenceNumber,
                final int maxFileSize) {
            this.valid = valid;
            this.open = open;
            this.ioError = ioError;
            this.sequenceNumber = sequenceNumber;
            this.maxFileSize = maxFileSize;
        }
    }

    static Meta read(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return new Meta(false, false, false, 0, 0);
        }
        final ByteBuffer bb = ByteBuffer.allocateDirect(DbMetaDataFlyweight.LENGTH);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (bb.hasRemaining()) {
                if (channel.read(bb) < 0) {
                    break;
                }
            }
        }
        if (bb.position() < DbMetaDataFlyweight.LENGTH) {
            return new Meta(false, false, false, 0, 0);
        }
        final UnsafeBuffer buffer = new UnsafeBuffer(bb);
        final DbMetaDataFlyweight meta = new DbMetaDataFlyweight().wrap(buffer, 0);
        if (!meta.verifyChecksum()) {
            return new Meta(false, false, false, 0, 0);
        }
        return new Meta(true, meta.open(), meta.ioError(), meta.sequenceNumber(), meta.maxFileSize());
    }

    static void write(
            final Path path,
            final boolean open,
            final boolean ioError,
            final long sequenceNumber,
            final int maxFileSize)
            throws IOException {
        final ByteBuffer bb = ByteBuffer.allocateDirect(DbMetaDataFlyweight.LENGTH);
        final UnsafeBuffer buffer = new UnsafeBuffer(bb);
        new DbMetaDataFlyweight()
                .wrap(buffer, 0)
                .version(Format.META_VERSION)
                .open(open)
                .ioError(ioError)
                .sequenceNumber(sequenceNumber)
                .maxFileSize(maxFileSize)
                .storeChecksum();
        bb.clear().limit(DbMetaDataFlyweight.LENGTH);
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (bb.hasRemaining()) {
                channel.write(bb);
            }
            channel.force(true);
        }
    }
}
