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

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.http.HttpTimeoutException;
import java.nio.file.*;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveFileReaderTests {
    private HostedRepository withFileContentsFailure(HostedRepository repository, IOException failure) {
        return (HostedRepository) Proxy.newProxyInstance(
                HostedRepository.class.getClassLoader(),
                new Class<?>[] { HostedRepository.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("fileContents")) {
                        throw new UncheckedIOException(failure);
                    }
                    try {
                        return method.invoke(repository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    void fallbackToSeedAfterTimeout(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var sourceFolder = new TemporaryDirectory();
             var seedFolder = new TemporaryDirectory()) {
            var archive = credentials.getHostedRepository();
            var mboxPath = Path.of("repo", "1.mbox");
            Files.createDirectories(sourceFolder.path().resolve(mboxPath).getParent());
            var source = CheckableRepository.init(sourceFolder.path(), archive.repositoryType(), mboxPath,
                                                  Set.of(), Set.of(), "0.1");
            var archiveRef = "master";
            source.push(source.head(), archive.authenticatedUrl(), archiveRef, true);

            var restReader = new ArchiveFileReader(archive, archiveRef, seedFolder.path());
            assertEquals(Files.readString(sourceFolder.path().resolve(mboxPath)),
                         restReader.fileContents(mboxPath.toString()).orElseThrow());
            try (var seedFiles = Files.list(seedFolder.path())) {
                assertEquals(0, seedFiles.count());
            }

            var timeoutArchive = withFileContentsFailure(archive, new HttpTimeoutException("request timed out"));
            var reader = new ArchiveFileReader(timeoutArchive, archiveRef, seedFolder.path());

            assertEquals(Files.readString(sourceFolder.path().resolve(mboxPath)),
                         reader.fileContents(mboxPath.toString()).orElseThrow());

            CheckableRepository.appendAndCommit(source, "Another archived message");
            source.push(source.head(), archive.authenticatedUrl(), archiveRef);

            assertEquals(Files.readString(sourceFolder.path().resolve(mboxPath)),
                         reader.fileContents(mboxPath.toString()).orElseThrow());
        }
    }

    @Test
    void doNotFallbackForOtherIoFailures(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var seedFolder = new TemporaryDirectory()) {
            var archive = credentials.getHostedRepository();
            var failure = new IOException("not a timeout");
            var failingArchive = withFileContentsFailure(archive, failure);
            var reader = new ArchiveFileReader(failingArchive, "master", seedFolder.path());

            var thrown = assertThrows(UncheckedIOException.class, () -> reader.fileContents("repo/1.mbox"));
            assertSame(failure, thrown.getCause());
        }
    }
}
