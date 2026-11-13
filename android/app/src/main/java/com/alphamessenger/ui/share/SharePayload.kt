package com.alphamessenger.ui.share

import android.net.Uri

/**
 * What came in from the system Sharesheet — exactly what the intent held.
 *
 * No parsing, no copies: reading URIs, detecting the type and (when the
 * system grants no persistent permission) making a local copy is the job of
 * [com.alphamessenger.ui.media.draft.MediaDraftFactory], shared by all sources
 * (copying here as well would duplicate every file and litter cache/share/).
 */
data class SharePayload(
    val uris: List<Uri>,
    val text: String?,
) {
    val isEmpty: Boolean get() = uris.isEmpty() && text.isNullOrBlank()
}
