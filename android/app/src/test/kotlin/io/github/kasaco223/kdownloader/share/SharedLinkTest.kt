// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.share

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedLinkTest {
    @Test
    fun takesTheFirstLinkOfTheFirstTextThatHasOne() {
        val link = SharedLink.fromTexts(
            listOf(
                "Mira este video",
                "https://youtu.be/aqz-KE-bpKQ?si=abc y https://vimeo.com/76979871",
                "https://youtu.be/aqz-KE-bpKQ?si=abc", // el ClipData suele repetir EXTRA_TEXT
            ),
        )
        assertEquals(SharedLink(url = "https://youtu.be/aqz-KE-bpKQ?si=abc", total = 2), link)
    }

    @Test
    fun acceptsADomainTypedByHand() {
        assertEquals(SharedLink("https://youtu.be/aqz-KE-bpKQ", 1), SharedLink.fromTexts(listOf("youtu.be/aqz-KE-bpKQ")))
    }

    @Test
    fun noLinkMeansNoUrl() {
        assertEquals(SharedLink(null, 0), SharedLink.fromTexts(listOf("hola", null, "ftp://example.com/x")))
        assertEquals(SharedLink(null, 0), SharedLink.fromTexts(emptyList()))
    }
}
