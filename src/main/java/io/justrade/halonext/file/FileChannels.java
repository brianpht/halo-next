/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.agrona.concurrent.UnsafeBuffer;

/** Internal file-channel helpers. */
final class FileChannels {

    private FileChannels() {}

    /** Reads an entire channel into a direct buffer so CRC32C can be verified during replay. */
    static UnsafeBuffer readAll(final FileChannel channel) throws IOException {
        final int size = (int) channel.size();
        final ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        long position = 0;
        while (buffer.hasRemaining()) {
            final int read = channel.read(buffer, position);
            if (read < 0) {
                break;
            }
            position += read;
        }
        return new UnsafeBuffer(buffer, 0, buffer.position());
    }
}
