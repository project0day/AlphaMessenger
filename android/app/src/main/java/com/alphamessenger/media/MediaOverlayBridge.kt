package com.alphamessenger.media

import com.alphamessenger.transfer.FileUiState
import com.alphamessenger.transfer.TransferDirection
import com.alphamessenger.transfer.TransferProgress

/**
 * DUMB 1:1 adapter: [MediaState] → [FileUiState] for the existing overlay renderer
 * ([com.alphamessenger.transfer.MediaSurface]). This is NOT a second brain — it does
 * no derivation; it just re-labels the ONE already-decided [MediaState.stage] into
 * the enum the overlay view currently draws. It exists only so the polished ring/
 * badge visuals keep working while bubbles migrate to [MediaState]; it is deleted in
 * the final stage once the overlay takes a [MediaState] directly.
 */
fun MediaState.toOverlayState(isOutgoing: Boolean): FileUiState = when (stage) {
    TransferStage.Uploading -> FileUiState.Uploading(TransferProgress(bytes, total))
    TransferStage.Downloading -> FileUiState.Downloading(TransferProgress(bytes, total))
    TransferStage.Paused -> FileUiState.Paused(TransferProgress(bytes, total))
    TransferStage.WaitingNetwork -> FileUiState.WaitingNetwork(TransferProgress(bytes, total))
    TransferStage.Ready, TransferStage.Uploaded -> FileUiState.Ready(localPath, playable)
    TransferStage.NeedDownload -> FileUiState.NeedDownload
    TransferStage.Failed ->
        FileUiState.Failed(if (isOutgoing) TransferDirection.UPLOAD else TransferDirection.DOWNLOAD)
    TransferStage.Gone -> FileUiState.Gone()
    TransferStage.UnknownServer -> FileUiState.Gone(unknownServer = true)
    TransferStage.Idle, TransferStage.Canceled -> FileUiState.Hidden
}
