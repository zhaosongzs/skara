/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.jcheck;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestableRepository;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.VCS;
import org.openjdk.skara.vcs.openjdk.CommitMessage;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CopyrightCheckTests {

    private static JCheckConfiguration conf() {
        return JCheckConfiguration.parse(List.of(
                "[general]",
                "project = test",
                "[checks]",
                "error = copyright"
        ));
    }

    private static CommitMessage message(Commit c) {
        return CommitMessageParsers.v1.parse(c);
    }

    @Test
    void CopyrightFormatIssueWithTrailingWhiteSpace() throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = TestableRepository.init(dir.path(), VCS.GIT);

            var afile = dir.path().resolve("a.java");
            Files.write(afile, List.of("/*\n" +
                    " * Copyright (c) 2024,  Oracle and/or its affiliates. All rights reserved.\n" +
                    " * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.\n" +
                    " */\n"));
            r.add(afile);
            var first = r.commit("1: Added a.java", "duke", "duke@openjdk.org");

            var check = new CopyrightCheck(r);
            var commit = r.lookup(first).orElseThrow();
            var issue = (CopyrightIssue) check.check(commit, message(commit), conf(), null).next();
            assertTrue(issue.filesWithCopyrightYearIssue.isEmpty());
            assertEquals(1, issue.filesWithCopyrightFormatIssue.size());

            // Now, remove the trailing whitespace
            Files.write(afile, List.of("/*\n" +
                    " * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.\n" +
                    " * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.\n" +
                    " */\n"));
            r.add(afile);
            var second = r.commit("2: Modified a.java", "duke", "duke@openjdk.org");
            check = new CopyrightCheck(r);
            commit = r.lookup(second).orElseThrow();
            assertFalse(check.check(commit, message(commit), conf(), null).hasNext());
        }
    }

    @Test
    void CopyrightYearIssue() throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = TestableRepository.init(dir.path(), VCS.GIT);

            var afile = dir.path().resolve("a.java");
            Files.write(afile, List.of("/*\n" +
                    " * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.\n" +
                    " * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.\n" +
                    " */\n"));
            r.add(afile);
            var first = r.commit("1: Added a.java", "duke", "duke@openjdk.org");

            var check = new CopyrightCheck(r);
            var commit = r.lookup(first).orElseThrow();
            var issue = (CopyrightIssue) check.check(commit, message(commit), conf(), null).next();
            assertEquals(1, issue.filesWithCopyrightYearIssue.size());
            assertTrue(issue.filesWithCopyrightFormatIssue.isEmpty());

            // Update the copyright year
            Files.write(afile, List.of("/*\n" +
                    " * Copyright (c) 2022, 2024, Oracle and/or its affiliates. All rights reserved.\n" +
                    " * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.\n" +
                    " */\n"));
            r.add(afile);
            var second = r.commit("2: Modified a.java", "duke", "duke@openjdk.org");
            check = new CopyrightCheck(r);
            commit = r.lookup(second).orElseThrow();
            assertFalse(check.check(commit, message(commit), conf(), null).hasNext());
        }
    }
}
