package com.alphamessenger.ui.chat.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupBlocksTest {

    @Test
    fun underlineIsMarkup() {
        val p = Markup.parse("meet ++on friday++ ok")
        assertEquals("meet on friday ok", p.text)
        assertEquals(listOf(Markup.Span(5, 14, MarkdownKind.UNDERLINE)), p.spans)
    }

    @Test
    fun aFenceSplitsProseAndCode() {
        val raw = "Like this:\n```kotlin\nval a = 1\njob.cancel()\n```\nthat's it"
        assertTrue(Markup.hasBlock(raw))
        assertEquals(
            listOf(
                Markup.Block.Prose("Like this:"),
                Markup.Block.Code("kotlin", "val a = 1\njob.cancel()"),
                Markup.Block.Prose("that's it"),
            ),
            Markup.blocks(raw),
        )
        // Previews read the code without its fences.
        assertEquals("Like this:\nval a = 1\njob.cancel()\nthat's it", Markup.plain(raw))
    }

    @Test
    fun aOneLineFenceHasNoLanguage() {
        assertEquals(listOf(Markup.Block.Code("", "adb install app.apk")), Markup.blocks("```adb install app.apk```"))
    }

    @Test
    fun plusesInProseAreNotUnderline() {
        assertEquals("C++ and C++", Markup.plain("C++ and C++"))
        assertEquals("C++/C++", Markup.plain("C++/C++"))
        assertEquals("i++ + j++", Markup.plain("i++ + j++"))
        assertEquals("a b", Markup.plain("++a b++"))
        assertFalse(Markup.hasBlock("two `ticks` only"))
    }

    @Test
    fun searchMatchesWordStartsInAnyCase() {
        assertTrue(SearchMatch.matches("Передай Привет Маше", "прив маш"))
        assertFalse(SearchMatch.matches("непривет", "прив"))
        assertEquals(listOf(8 until 12), SearchMatch.hits("Передай Привет", "прив"))
    }
}
