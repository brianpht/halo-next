/*
 * Copyright 2026 halo-next authors
 * Licensed under the terms of the Apache License 2.0. Please refer to the accompanying LICENSE file.
 */

package io.justrade.halonext.api;

/** Checked exception for all public HaloDB operations. */
public class HaloDBException extends Exception {

    private static final long serialVersionUID = 1L;

    public HaloDBException(final String message) {
        super(message);
    }

    public HaloDBException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public HaloDBException(final Throwable cause) {
        super(cause);
    }
}
