/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

/**
 * DirectBuffer / UnsafeBuffer flyweights and codecs for the on-disk formats
 * (record, index entry, tombstone entry, db metadata). All structures are read
 * and written as Agrona flyweights via wrap(buffer, offset); little-endian only.
 */
package io.justrade.halonext.buffer;
