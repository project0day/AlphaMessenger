package com.alphamessenger.ui.gif

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Who plays the GIFs in a chat: a small pool of players made once and reused,
 * never one per bubble. A GIF on screen asks for a player once the list stands
 * still; while it scrolls nobody new gets one (the posters show), so scrolling
 * never builds a player or a decoder. With more GIFs on screen than players,
 * the one that waited longest gives its player up and shows its poster.
 */
object GifPlayback {
    private const val SLOTS = 2

    private class Slot(val player: ExoPlayer) {
        var owner by mutableStateOf<String?>(null)
        var since = 0L
    }

    private var slots: List<Slot> = emptyList()

    /** The chat list is moving: no GIF starts now. */
    var scrolling by mutableStateOf(false)

    private fun pool(ctx: Context): List<Slot> {
        if (slots.isEmpty()) {
            slots = List(SLOTS) {
                Slot(ExoPlayer.Builder(ctx.applicationContext).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(ctx.applicationContext)).build().apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_ALL
                })
            }
        }
        return slots
    }

    /** The player [id] holds, if it holds one (read in composition: follows changes). */
    fun playerOf(id: String): Player? = slots.firstOrNull { it.owner == id }?.player

    /** Give [id] a player for [path]: its own, a free one, or the longest-held one. */
    fun acquire(ctx: Context, id: String, path: String) {
        val pool = pool(ctx)
        if (pool.any { it.owner == id }) return
        val slot = pool.firstOrNull { it.owner == null } ?: pool.minBy { it.since }
        slot.owner = id
        slot.since = System.nanoTime()
        slot.player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        slot.player.prepare()
        slot.player.playWhenReady = true
    }

    /** [id] left the screen: its player is free again. */
    fun release(id: String) {
        slots.firstOrNull { it.owner == id }?.let {
            it.owner = null
            it.player.stop()
            it.player.clearMediaItems()
        }
    }

    /** The chat closed: the players go too. */
    fun releaseAll() {
        slots.forEach { it.owner = null; it.player.release() }
        slots = emptyList()
        scrolling = false
    }
}
