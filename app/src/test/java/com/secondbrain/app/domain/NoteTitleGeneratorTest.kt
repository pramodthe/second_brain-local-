package com.secondbrain.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTitleGeneratorTest {

    @Test
    fun usesFirstMeaningfulLineAndRemovesMarkdownPrefix() {
        assertEquals(
            "Build the daily capture screen",
            NoteTitleGenerator.fromContent("\n  ## Build the daily capture screen\nDetails follow")
        )
        assertEquals("Call the design team", NoteTitleGenerator.fromContent("- [ ] Call the design team"))
    }

    @Test
    fun shortensLongTitlesAtAWordBoundary() {
        val title = NoteTitleGenerator.fromContent(
            "This is a deliberately long note opening that should become a readable compact title without cutting a word in half"
        )

        assertTrue(title.length <= 72)
        assertTrue(title.endsWith("…"))
        assertTrue(!title.dropLast(1).endsWith(" "))
    }

    @Test
    fun emptyContentStaysUntitledForVoiceTranscription() {
        assertEquals("", NoteTitleGenerator.fromContent("  \n "))
    }
}
