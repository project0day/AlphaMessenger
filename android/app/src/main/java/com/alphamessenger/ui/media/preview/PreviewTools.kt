package com.alphamessenger.ui.media.preview

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.alphamessenger.R
import com.alphamessenger.ui.media.draft.MediaDraftItem
import com.alphamessenger.ui.media.draft.MediaDraftStore

/**
 * Preview edit tools as a LIST, not a set of buttons in markup.
 *
 * There will be many tools: rotate, flip, crop, drawing, text, stickers,
 * video trim, sound. Hardcoding each into the top bar means a markup edit,
 * another `if` on attachment type, and another button that does nothing for
 * a document. So a tool here is DATA: it knows what it applies to and what
 * it does, and the bar simply shows the fitting ones. Adding a tool = adding
 * one entry.
 *
 * The row is not endless, hence the bar limit ([VISIBLE_TOOLS]): the first
 * tools are shown as buttons, the rest go into "⋮". List order is priority —
 * what is needed more often sits higher.
 *
 * Tools come in two kinds, and the difference matters:
 *  - QUICK ([PreviewTool.action]) edits the item in place — rotate, flip,
 *    sound. `MediaOutputFactory` applies them to the file in the send queue;
 *  - HEAVY ([PreviewTool.opens]) opens its own screen — crop, drawing, text —
 *    and returns a finished file to the set.
 */
data class PreviewTool(
    val id: String,
    val icon: ImageVector,
    @StringRes val labelRes: Int,
    /** Whether to show the tool for this attachment. */
    val appliesTo: (MediaDraftItem) -> Boolean,
    /** Quick in-place edit. Empty for tools with their own screen. */
    val action: ((MediaDraftStore, MediaDraftItem) -> Unit)? = null,
    /** Editor screen to open. Empty for quick edits. */
    val opens: PreviewEditor? = null,
)

/** Editors with their own screen. Shown one at a time; the bar is ready for them. */
enum class PreviewEditor { CROP, DRAW, TEXT, TRIM }

object PreviewToolbox {

    /** How many tools fit as buttons; the rest go into "⋮". */
    const val VISIBLE_TOOLS = 3

    /** Edit only what we can apply to the file at send time. */
    private fun isEditableImage(item: MediaDraftItem): Boolean =
        item.kind == MediaDraftItem.Kind.IMAGE && !item.sendAsFile

    /**
     * The photo's only tool is the pencil. Everything a frame supports lives
     * INSIDE the editor: crop, rotate, flip, ratios, later drawing and text. No
     * button-per-edit in the preview header — ten edits would mean ten icons.
     */
    private val edit = PreviewTool(
        id = "edit",
        icon = AlphaIcons.Outlined.Edit,
        labelRes = R.string.preview_edit,
        appliesTo = ::isEditableImage,
        opens = PreviewEditor.CROP,
    )
    /** Order = priority in the bar. New tools are appended to this list. */
    /** The photo's only tool. Everything else is INSIDE the editor. */
    /** Video gets the same pencil: which editor to open is decided by attachment type. */
    private val editVideo = PreviewTool(
        id = "edit_video",
        icon = AlphaIcons.Outlined.Edit,
        labelRes = R.string.preview_edit,
        appliesTo = { it.kind == MediaDraftItem.Kind.VIDEO && !it.sendAsFile },
        opens = PreviewEditor.TRIM,
    )

    private fun isPictureOrVideo(item: MediaDraftItem) =
        item.kind == MediaDraftItem.Kind.IMAGE || item.kind == MediaDraftItem.Kind.VIDEO

    /** Send the original as a file: no compression, no edits; the recipient gets a document. */
    private val asFile = PreviewTool(
        id = "as_file",
        icon = AlphaIcons.AutoMirrored.Outlined.InsertDriveFile,
        labelRes = R.string.preview_send_as_file,
        appliesTo = { isPictureOrVideo(it) && !it.sendAsFile },
        action = { store, item -> store.update(item.id) { it.copy(sendAsFile = true) } },
    )
    private val asMedia = PreviewTool(
        id = "as_media",
        icon = AlphaIcons.Outlined.Image,
        labelRes = R.string.preview_send_as_media,
        appliesTo = { isPictureOrVideo(it) && it.sendAsFile },
        action = { store, item -> store.update(item.id) { it.copy(sendAsFile = false) } },
    )

    private val all: List<PreviewTool> = listOf(edit, editVideo, asFile, asMedia)

    fun forItem(item: MediaDraftItem?): List<PreviewTool> =
        if (item == null) emptyList() else all.filter { it.appliesTo(item) }

    /** A gallery picture not picked yet offers its pencil only: using it picks the picture. */
    fun forGallery(item: com.alphamessenger.ui.chat.GalleryItem?): List<PreviewTool> =
        if (item == null) emptyList() else listOf(if (item.isVideo) editVideo else edit)
}
