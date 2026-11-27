package com.alphamessenger.ui.media.send

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposedSetTest {

    private fun slot(key: String, draft: String = key.substringBefore('|'), op: String = "op-$key") =
        ComposedSet.Slot(key, draft, op)

    @Test
    fun aNewItemStartsAndKeptOnesStay() {
        val a = slot("a|1")
        val change = ComposedSet.change(listOf(a), wanted = listOf("a|1", "b|1"), reserved = emptySet())
        assertEquals(listOf(a), change.keep)
        assertEquals(emptyList<ComposedSet.Slot>(), change.drop)
        assertEquals(listOf("b|1"), change.add)
    }

    @Test
    fun anEditedItemRestartsUnderItsNewKey() {
        val before = slot("a|1")
        val change = ComposedSet.change(listOf(before), wanted = listOf("a|2"), reserved = emptySet())
        assertEquals("the old upload goes", listOf(before), change.drop)
        assertEquals(listOf("a|2"), change.add)
    }

    @Test
    fun aRemovedItemLeaves() {
        val a = slot("a|1")
        val b = slot("b|1")
        val change = ComposedSet.change(listOf(a, b), wanted = listOf("b|1"), reserved = emptySet())
        assertEquals(listOf(a), change.drop)
        assertEquals(listOf(b), change.keep)
    }

    @Test
    fun anItemHandedToSendIsKeptWhateverTheComposerReports() {
        val a = slot("a|1")
        val change = ComposedSet.change(listOf(a), wanted = emptyList(), reserved = setOf("a"))
        assertEquals(listOf(a), change.keep)
        assertEquals(emptyList<ComposedSet.Slot>(), change.drop)
    }

    @Test
    fun aSendReusesItsComposedItemsInItsOwnOrderAndLeavesTheRest() {
        val a = slot("a|1")
        val b = slot("b|1")
        val c = slot("c|1")
        val (reused, left) = ComposedSet.take(listOf("c|1", "new|1", "a|1"), listOf(a, b, c))
        assertEquals("same uploads, the send's order; a new item has none", listOf(c, null, a), reused)
        assertEquals("what the send does not take leaves the servers", listOf(b), left)
    }

    @Test
    fun aSendUnderOtherSettingsReusesNothing() {
        val a = slot("a|1|v1080")
        val (reused, left) = ComposedSet.take(listOf("a|1|v720"), listOf(a))
        assertEquals(listOf<ComposedSet.Slot?>(null), reused)
        assertEquals(listOf(a), left)
    }
}
