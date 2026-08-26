/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.forge.*;

import java.io.*;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import java.util.logging.Logger;

class ArchiveFileReader {
    private final HostedRepository archiveRepository;
    private final String archiveRef;
    private final HostedRepositoryPool repositoryPool;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    ArchiveFileReader(HostedRepository archiveRepository, String archiveRef, Path seedStorage) {
        this.archiveRepository = archiveRepository;
        this.archiveRef = archiveRef;
        repositoryPool = new HostedRepositoryPool(seedStorage);
    }

    private boolean causedByTimeout(UncheckedIOException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof HttpTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    Optional<String> fileContents(String filename) {
        try {
            return archiveRepository.fileContents(filename, archiveRef);
        } catch (UncheckedIOException e) {
            if (!causedByTimeout(e)) {
                throw e;
            }
            log.warning("REST request for archive file " + filename + " timed out, reading from local seed");
        }

        synchronized (this) {
            try {
                var seedRepository = repositoryPool.seedRepository(archiveRepository, false);
                var archiveHash = seedRepository.resolve(archiveRef)
                                                    .orElseThrow(() -> new IOException("Unknown archive ref: " + archiveRef));
                return seedRepository.show(Path.of(filename), archiveHash)
                                     .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
