package com.alphamessenger.ui.media.send

/**
 * What a set being composed keeps, drops and adds, and what a send takes from it —
 * decided on keys alone, so the rules hold apart from files, URIs and the network.
 *
 * A key names everything that shapes an item's file (the draft, its edits, the
 * quality): an item stays only while its key is still wanted, or once it was handed
 * to Send; a send reuses an item only under the same key.
 */
internal object ComposedSet {

    /** One composed item, as the rules see it. */
    data class Slot(val key: String?, val draftId: String, val op: String?)

    data class Change(val keep: List<Slot>, val drop: List<Slot>, val add: List<String>)

    /** The set now wants [wanted]; items of drafts in [reserved] belong to a send already. */
    fun change(current: List<Slot>, wanted: Collection<String>, reserved: Set<String>): Change {
        val (keep, drop) = current.partition { it.key in wanted || it.draftId in reserved }
        val have = keep.mapNotNull { it.key }.toSet()
        return Change(keep, drop, wanted.filter { it !in have }.distinct())
    }

    /**
     * For each key of the send, in its order, the composed item it reuses (null: a new
     * one), and the composed items the send leaves, which then leave the servers.
     */
    fun take(sendKeys: List<String>, composed: List<Slot>): Pair<List<Slot?>, List<Slot>> {
        val byKey = composed.associateBy { it.key }
        val reused = sendKeys.map { byKey[it] }
        val used = reused.filterNotNull().map { it.op }.toSet()
        return reused to composed.filter { it.op !in used }
    }
}

internal fun MediaSendItem.slot() = ComposedSet.Slot(preUploadKey, draft.id, uploadOp)
