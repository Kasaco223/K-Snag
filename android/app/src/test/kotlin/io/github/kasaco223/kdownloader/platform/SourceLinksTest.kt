// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLinksTest {
    private val repo = "https://github.com/Kasaco223/K-Snag"

    @Test
    fun releasesLinkToTheirTag() {
        assertEquals("$repo/tree/android-v1.0.0/android", SourceLinks.sourceTree(repo, "release", "1.0.0", "dev"))
        assertEquals(
            "$repo/tree/android-v1.0.0-rc.2/android",
            SourceLinks.sourceTree(repo, "release", "1.0.0-rc.2", "0123456789abcdef0123456789abcdef01234567"),
        )
    }

    @Test
    fun otherBuildsLinkToTheirCommit() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        assertEquals("$repo/tree/$sha", SourceLinks.sourceTree(repo, "debug", "1.0.0-rc.1-debug", sha))
        assertEquals("$repo/tree/$sha", SourceLinks.sourceTree(repo, "qa", "1.0.0-rc.1-qa", sha))
    }

    @Test
    fun localBuildsWithoutACommitLinkToMaster() {
        assertEquals("$repo/tree/master/android", SourceLinks.sourceTree(repo, "debug", "1.0.0-rc.1-debug", "dev"))
    }

    @Test
    fun isCommitAcceptsOnlyGitHashes() {
        assertTrue(SourceLinks.isCommit("3d0e907"))
        assertTrue(SourceLinks.isCommit("0123456789abcdef0123456789abcdef01234567"))
        assertFalse(SourceLinks.isCommit("dev"))
        assertFalse(SourceLinks.isCommit("3D0E907")) // git siempre los escribe en minúsculas
        assertFalse(SourceLinks.isCommit("3d0e90"))
        assertFalse(SourceLinks.isCommit("0123456789abcdef0123456789abcdef012345678"))
        assertFalse(SourceLinks.isCommit("../../evil"))
    }
}
