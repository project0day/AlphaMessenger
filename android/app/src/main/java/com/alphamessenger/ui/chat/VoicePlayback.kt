package com.alphamessenger.ui.chat

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.PowerManager
import com.alphamessenger.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One message in the chat order — lets the voice player know adjacency so it
 *  only auto-advances across CONSECUTIVE voice notes (stops at any other type). */
data class VoiceSeqItem(val id: String, val isVoice: Boolean, val ref: String?, val chatId: String = "")

/** What a track's file says about itself: tags and a small cover. */
data class TrackInfo(val title: String?, val artist: String?, val cover: android.graphics.Bitmap?)

/** How the music list plays: in order or shuffled; repeat off, all, or one track. */
data class MusicMode(val shuffle: Boolean = false, val repeat: Int = REPEAT_OFF) {
    companion object { const val REPEAT_OFF = 0; const val REPEAT_ALL = 1; const val REPEAT_ONE = 2 }
}

/** A music track (with its source chat) for the global mini-player. */
data class MusicTrack(val id: String, val ref: String, val title: String, val chatId: String)

/** Snapshot of a shared audio player (voice or music). */
data class PlaybackSnapshot(
    val currentId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    /** Display title for the music mini-player (filename). */
    val title: String = "",
)

/**
 * One shared player for ALL voice notes in a chat. Enables:
 *  - **Sequential auto-play**: when a voice finishes, the next CONSECUTIVE voice
 *    starts, after a short tone; the last one ends with its own tone.
 *  - **Raise-to-ear**: while a voice plays (no headset), a proximity wake lock blanks
 *    the screen at the ear and the player itself turns to the call stream; taking it
 *    away turns it back and pauses.
 *
 * The file is read in place (sealed cache included) by the media player, so playing
 * starts without first copying the whole note out.
 * Music files keep their own player — only voice goes through here.
 */
@androidx.compose.runtime.Stable
class VoicePlaybackController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    private var ticker: Job? = null
    private val prefs = context.getSharedPreferences("alpha_playback", Context.MODE_PRIVATE)
    /** One speed for every voice note, kept across launches. */
    private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1f))
    val speed: StateFlow<Float> = _speed.asStateFlow()
    /** Full chat message order (with isVoice flags) so auto-advance only
     *  crosses consecutive voice notes. */
    private var sequence: List<VoiceSeqItem> = emptyList()
    private var loader: (suspend (String) -> String?)? = null
    /** The voice the tone now playing leads to; null while a voice itself plays. */
    private var afterTone: String? = null
    /** The closing tone plays: the note is over. */
    private var ending = false
    /** Where on its scale the note was asked to start (0..1), applied once it is ready. */
    private var startFrom = 0f

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximity: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val wakeLock: PowerManager.WakeLock? = runCatching {
        power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "alpha:voice")
    }.getOrNull()
    /** When the ear watch began: readings right after it are the hand, not the ear. */
    private var watchSince = -1L
    private var atEar = false
    /** The player is stopped for a moment to change its output (ear / speaker). */
    private var switchingOutput = false

    private val player: androidx.media3.exoplayer.ExoPlayer by lazy {
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(context))
            .setAudioAttributes(SPEAKER, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(listener) }
    }

    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (afterTone == null && !ending && _state.value.currentId != null) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (isPlaying) startTicker()
            }
            // Our own stop for the output switch is not the end of listening.
            if (isPlaying) watchEar() else if (!player.playWhenReady && !switchingOutput) stopEarWatch()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                androidx.media3.common.Player.STATE_READY -> if (afterTone == null && !ending) {
                    _state.value = _state.value.copy(durationMs = player.duration.coerceAtLeast(0).toInt())
                    // The length is known only now: a start picked on the scale applies here.
                    if (startFrom > 0f) { player.seekTo((startFrom * player.duration.coerceAtLeast(0)).toLong()); startFrom = 0f }
                }
                androidx.media3.common.Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }
    }

    fun configure(sequence: List<VoiceSeqItem>, loader: suspend (String) -> String?) {
        this.sequence = sequence
        this.loader = loader
    }

    /** Source chat of the currently-playing voice (for the global bar's tap-to-open). */
    fun sourceChatId(): String? =
        sequence.firstOrNull { it.id == _state.value.currentId }?.chatId?.takeIf { it.isNotBlank() }

    /** Pause if currently playing (used when starting a recording). */
    fun pausePlayback() { if (_state.value.isPlaying) pause() }

    fun setSpeed(s: Float) {
        _speed.value = s
        prefs.edit().putFloat(KEY_SPEED, s).apply()
        if (afterTone == null && !ending) player.setPlaybackSpeed(s)
    }

    fun toggle(id: String) {
        val st = _state.value
        if (st.currentId == id && afterTone == null && !ending && player.mediaItemCount > 0) {
            if (st.isPlaying) pause() else resume()
        } else play(id)
    }

    fun seek(id: String, fraction: Float) {
        // Another note (or this one over): it starts from the point tapped on its scale.
        if (_state.value.currentId != id || afterTone != null || ending || player.mediaItemCount == 0) {
            play(id, fraction)
            return
        }
        val target = (fraction * player.duration.coerceAtLeast(0)).toLong()
        player.seekTo(target)
        _state.value = _state.value.copy(positionMs = target.toInt())
    }

    private fun play(id: String, from: Float = 0f) {
        val ref = sequence.firstOrNull { it.id == id && it.isVoice }?.ref ?: return
        val ldr = loader ?: return
        // Shown as the current note at once: the tap answers before the file is found.
        _state.value = PlaybackSnapshot(id, true, 0, 0, context.getString(R.string.voice_note_label))
        scope.launch {
            val path = ldr(ref) ?: return@launch
            if (_state.value.currentId != id) return@launch
            afterTone = null
            ending = false
            player.setAudioAttributes(if (atEar) EARPIECE else SPEAKER, false)
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path))))
            startFrom = from.coerceIn(0f, 0.99f)
            player.setPlaybackSpeed(_speed.value)
            player.prepare()
            player.play()
            startTicker()
        }
    }

    /** Skip to the next CONSECUTIVE voice note (mini-player ⏭). */
    fun next() {
        nextVoice(_state.value.currentId ?: return)?.let { play(it) }
    }

    /** Previous voice note (mini-player ⏮). Restart the current one if we're
     *  more than 3s in, otherwise step back to the previous CONSECUTIVE voice. */
    fun prev() {
        val cur = _state.value.currentId ?: return
        if (_state.value.positionMs > 3000) { play(cur); return }
        val idx = sequence.indexOfFirst { it.id == cur }
        val prv = if (idx > 0) sequence.getOrNull(idx - 1) else null
        if (prv != null && prv.isVoice && prv.ref != null) play(prv.id) else play(cur)
    }

    /** Stop and dismiss the global voice mini-player (✕). */
    fun close() {
        afterTone = null
        ending = false
        ticker?.cancel(); ticker = null
        runCatching { player.stop(); player.clearMediaItems() }
        stopEarWatch()
        _state.value = PlaybackSnapshot()
    }

    /** The next message is a voice note too: auto-advance crosses only those. */
    private fun nextVoice(id: String): String? {
        val idx = sequence.indexOfFirst { it.id == id }
        val next = if (idx >= 0) sequence.getOrNull(idx + 1) else null
        return next?.takeIf { it.isVoice && it.ref != null }?.id
    }

    private fun onEnded() {
        val toneFor = afterTone
        when {
            toneFor != null -> { afterTone = null; play(toneFor) }
            ending -> close()
            else -> {
                // A voice ended: a tone in the same output, then the next note or the end.
                val finished = _state.value.currentId ?: return close()
                val next = nextVoice(finished)
                if (next != null) afterTone = next else ending = true
                ticker?.cancel()
                _state.value = _state.value.copy(isPlaying = next != null, positionMs = _state.value.durationMs)
                player.setPlaybackSpeed(1f)
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(rawUri(if (next != null) R.raw.voice_next else R.raw.voice_end)))
                player.prepare()
                player.play()
            }
        }
    }

    private fun rawUri(res: Int) = android.net.Uri.parse("android.resource://${context.packageName}/$res")

    private fun pause() {
        player.pause()
        _state.value = _state.value.copy(isPlaying = false)
    }

    private fun resume() {
        player.play()
        _state.value = _state.value.copy(isPlaying = true)
        startTicker() // restart the progress ticker (it exits when paused)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (_state.value.isPlaying) {
                if (afterTone == null && !ending) _state.value = _state.value.copy(positionMs = player.currentPosition.toInt())
                delay(120)
            }
        }
    }

    // ── Raise-to-ear (proximity) ──────────────────────────────────────
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (watchSince < 0 || System.currentTimeMillis() - watchSince <= EAR_SETTLE_MS) return
            if (headsetOn()) return
            val v = e.values.firstOrNull() ?: return
            val near = v < NEAR_CM && v != proximity?.maximumRange
            if (near == atEar) return
            atEar = near
            // The player itself moves to the call stream: the phone's audio mode stays
            // as it is, so nothing else is rerouted or muted.
            switchingOutput = true
            player.playWhenReady = false
            player.setAudioAttributes(if (near) EARPIECE else SPEAKER, false)
            switchingOutput = false
            if (near) {
                watchSince = System.currentTimeMillis()
                player.playWhenReady = true
            } else {
                // Away from the ear: the screen comes back and the note waits.
                _state.value = _state.value.copy(isPlaying = false)
                stopEarWatch()
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun headsetOn(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun watchEar() {
        if (proximity == null || watchSince >= 0 || headsetOn()) return
        watchSince = System.currentTimeMillis()
        runCatching { if (wakeLock?.isHeld == false) wakeLock.acquire(30 * 60 * 1000L) }
        sensors.registerListener(proximityListener, proximity, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopEarWatch() {
        if (watchSince < 0 && !atEar) return
        watchSince = -1L
        runCatching { sensors.unregisterListener(proximityListener) }
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        if (atEar) {
            atEar = false
            player.setAudioAttributes(SPEAKER, false)
        }
    }

    fun release() {
        close()
        player.release()
    }

    private companion object {
        val SPEAKER: androidx.media3.common.AudioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        val EARPIECE: androidx.media3.common.AudioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_VOICE_COMMUNICATION)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        /** Below this the phone is at the ear (cm), unless it is the sensor's "far" value. */
        const val NEAR_CM = 5f
        /** Readings this soon after the watch starts come from the hand, not the ear. */
        const val EAR_SETTLE_MS = 500L
    }
}

/**
 * Shared MUSIC player (separate from voice). Survives bubble scroll, powers a
 * classic mini-player: prev / play-pause / next / close, plus a track
 * list. No proximity / earpiece routing — music plays on the speaker.
 */
@androidx.compose.runtime.Stable
class MusicPlaybackController(context: Context) {
    private val prefs = context.getSharedPreferences("alpha_playback", Context.MODE_PRIVATE)
    /** One speed for every track, kept across launches. */
    private val _speed = MutableStateFlow(prefs.getFloat(KEY_MUSIC_SPEED, 1f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    /** Shown names by track id: "Artist — Title" from the file's tags once read. */
    private val _titles = MutableStateFlow<Map<String, String>>(emptyMap())
    val titles: StateFlow<Map<String, String>> = _titles.asStateFlow()

    /** Tags and covers by track id, for the list (filled as files become known). */
    private val _info = MutableStateFlow<Map<String, TrackInfo>>(emptyMap())
    val info: StateFlow<Map<String, TrackInfo>> = _info.asStateFlow()

    private val _mode = MutableStateFlow(MusicMode())
    val mode: StateFlow<MusicMode> = _mode.asStateFlow()

    /** The system player was tapped: the app shows the music sheet once it can. */
    private val _sheetPending = MutableStateFlow(false)
    val sheetPending: StateFlow<Boolean> = _sheetPending.asStateFlow()
    fun requestSheet() { _sheetPending.value = true }
    fun sheetShown() { _sheetPending.value = false }

    fun toggleShuffle() { _mode.value = _mode.value.copy(shuffle = !_mode.value.shuffle) }
    fun cycleRepeat() { _mode.value = _mode.value.copy(repeat = (_mode.value.repeat + 1) % 3) }

    /** A track's file is on the phone: read its tags and cover once. */
    fun learnFile(id: String, path: String) {
        if (_info.value.containsKey(id)) return
        scope.launch {
            val tags = kotlinx.coroutines.withContext(Dispatchers.IO) { readTags(path) }
            remember(id, tags)
        }
    }

    private fun remember(id: String, tags: Tags) {
        val cover = tags.art?.let { bytes ->
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            }.getOrNull()
        }
        _info.value = _info.value + (id to TrackInfo(tags.title, tags.artist, cover))
        tags.title?.let { t -> _titles.value = _titles.value + (id to (tags.artist?.let { "$it — $t" } ?: t)) }
    }

    private var ticker: Job? = null
    /** Tracks of the chat the current track plays from (drives next/prev/list).
     *  Stays put when you browse OTHER chats so playback context is preserved. */
    private var activeTracks: List<MusicTrack> = emptyList()
    /** Tracks of the chat currently on screen (becomes active when you start a
     *  track from it). */
    private var latestTracks: List<MusicTrack> = emptyList()
    private var loader: (suspend (String) -> String?)? = null
    private var sessionLink: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    /** One player for music, shared with the system media session (shade, lock screen, headset). */
    private val player: androidx.media3.exoplayer.ExoPlayer by lazy {
        androidx.media3.exoplayer.ExoPlayer.Builder(appContext).setMediaSourceFactory(com.alphamessenger.data.VaultPlayback.sources(appContext))
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(listener) }
    }

    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_state.value.currentId == null) return
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (isPlaying) startTicker()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            val id = _state.value.currentId ?: return
            when (playbackState) {
                androidx.media3.common.Player.STATE_READY ->
                    _state.value = _state.value.copy(durationMs = player.duration.coerceAtLeast(0).toInt())
                androidx.media3.common.Player.STATE_ENDED -> next0(id)
                else -> Unit
            }
        }
    }

    /** What the system session drives: our player, with next/previous going through our list. */
    fun sessionPlayer(): androidx.media3.common.Player = object : androidx.media3.common.ForwardingPlayer(player) {
        override fun getAvailableCommands(): androidx.media3.common.Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)
                .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
        override fun isCommandAvailable(command: Int): Boolean =
            command == androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT ||
                command == androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                command == androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS ||
                command == androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                super.isCommandAvailable(command)
        override fun seekToNext() = next()
        override fun seekToNextMediaItem() = next()
        override fun seekToPrevious() = prev()
        override fun seekToPreviousMediaItem() = prev()
    }

    /** The tracks of the currently-playing chat (for the mini-player list). */
    fun tracks(): List<MusicTrack> = activeTracks
    /** Chat id the current track belongs to (for "show in chat"). */
    fun sourceChatId(): String? = activeTracks.firstOrNull { it.id == _state.value.currentId }?.chatId

    fun configure(tracks: List<MusicTrack>, loader: suspend (String) -> String?) {
        this.loader = loader
        latestTracks = tracks
        // Adopt as active only when nothing is playing, or this list still
        // contains the playing track (i.e. we're back in its source chat).
        val cur = _state.value.currentId
        if (cur == null || tracks.any { it.id == cur }) activeTracks = tracks
    }

    fun setSpeed(s: Float) {
        _speed.value = s
        prefs.edit().putFloat(KEY_MUSIC_SPEED, s).apply()
        runCatching { player.setPlaybackSpeed(s) }
    }

    fun toggle(id: String) {
        val st = _state.value
        if (st.currentId == id && player.mediaItemCount > 0) {
            if (st.isPlaying) pause() else resume()
        } else {
            // Starting a NEW track → make sure its chat's list is the active
            // one (so next/prev/list reflect where you actually tapped).
            if (activeTracks.none { it.id == id } && latestTracks.any { it.id == id }) {
                activeTracks = latestTracks
            }
            play(id)
        }
    }

    fun seek(id: String, fraction: Float) {
        if (_state.value.currentId != id) return
        val target = (fraction * player.duration.coerceAtLeast(0)).toLong()
        runCatching { player.seekTo(target) }
        _state.value = _state.value.copy(positionMs = target.toInt())
    }

    /** Pause if currently playing (used when starting a recording). */
    fun pausePlayback() { if (_state.value.isPlaying) pause() }

    fun next() {
        val cur = _state.value.currentId ?: return
        followingOf(cur)?.let { play(it.id) }
    }

    /** The track after [cur] by the mode: shuffled, in order, or wrapping round on "repeat all". */
    private fun followingOf(cur: String): MusicTrack? {
        if (_mode.value.shuffle && activeTracks.size > 1) return activeTracks.filter { it.id != cur }.random()
        val idx = activeTracks.indexOfFirst { it.id == cur }
        return activeTracks.getOrNull(idx + 1)
            ?: activeTracks.firstOrNull().takeIf { _mode.value.repeat == MusicMode.REPEAT_ALL }
    }

    fun prev() {
        // Restart current if more than 3s in, else go to the previous track.
        if (_state.value.positionMs > 3000) { runCatching { player.seekTo(0) }; return }
        val cur = _state.value.currentId ?: return
        val idx = activeTracks.indexOfFirst { it.id == cur }
        activeTracks.getOrNull(idx - 1)?.let { play(it.id) } ?: runCatching { player.seekTo(0) }
    }

    private fun play(id: String) {
        val item = activeTracks.firstOrNull { it.id == id } ?: return
        val ldr = loader ?: return
        scope.launch {
            val path = ldr(item.ref) ?: return@launch
            val tags = kotlinx.coroutines.withContext(Dispatchers.IO) { readTags(path) }
            remember(id, tags)
            startFile(id, path, tags.title ?: item.title, tags.artist, tags.art)
        }
    }

    private class Tags(val title: String?, val artist: String?, val art: ByteArray?)

    private fun readTags(path: String): Tags = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            com.alphamessenger.data.MediaVault.setSource(r, path)
            Tags(
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() },
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() },
                r.embeddedPicture,
            )
        } finally { r.release() }
    }.getOrDefault(Tags(null, null, null))

    private fun startFile(id: String, path: String, title: String, artist: String?, art: ByteArray?) {
        ticker?.cancel(); ticker = null
        val meta = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .apply { if (art != null) setArtworkData(art, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            .build()
        val item = androidx.media3.common.MediaItem.Builder()
            .setMediaId(id)
            .setUri(android.net.Uri.fromFile(java.io.File(path)))
            .setMediaMetadata(meta)
            .build()
        _state.value = PlaybackSnapshot(id, true, 0, 0, _titles.value[id] ?: title)
        runCatching {
            player.setMediaItem(item)
            player.setPlaybackSpeed(_speed.value)
            player.prepare()
            player.play()
        }
        connectSession()
        startTicker()
    }

    /** Bind the system media session once: it shows the player in the shade and on the lock screen. */
    private fun connectSession() {
        if (sessionLink != null) return
        val token = androidx.media3.session.SessionToken(
            appContext, android.content.ComponentName(appContext, com.alphamessenger.service.MusicService::class.java),
        )
        sessionLink = androidx.media3.session.MediaController.Builder(appContext, token).buildAsync()
    }

    private fun next0(finishedId: String) {
        // The ticker must not write the end position back after this.
        ticker?.cancel(); ticker = null
        if (_mode.value.repeat == MusicMode.REPEAT_ONE) { play(finishedId); return }
        val nxt = followingOf(finishedId)
        if (nxt != null) play(nxt.id)
        else {
            // The list ended: back to the start, paused, ready for play.
            runCatching { player.pause(); player.seekTo(0) }
            _state.value = _state.value.copy(isPlaying = false, positionMs = 0)
        }
    }

    private fun pause() {
        runCatching { player.pause() }
        _state.value = _state.value.copy(isPlaying = false)
    }

    private fun resume() {
        runCatching {
            if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        _state.value = _state.value.copy(isPlaying = true)
        startTicker() // restart the progress ticker (it exits when paused)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (_state.value.isPlaying) {
                if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) break
                _state.value = _state.value.copy(positionMs = runCatching { player.currentPosition.toInt() }.getOrDefault(0))
                delay(200)
            }
        }
    }

    /** Stop and dismiss the mini-player (close button) and the system player. */
    fun close() {
        ticker?.cancel(); ticker = null
        runCatching { player.stop(); player.clearMediaItems() }
        _state.value = PlaybackSnapshot()
        sessionLink?.let { f -> runCatching { androidx.media3.session.MediaController.releaseFuture(f) } }
        sessionLink = null
    }

    fun release() = close()
}

/**
 * App-scoped holder so music keeps playing when you leave one chat and open
 * another (the controller is NOT tied to a single ChatScreen's lifecycle).
 */
object MusicPlayerHolder {
    @Volatile private var instance: MusicPlaybackController? = null
    fun get(context: Context): MusicPlaybackController =
        instance ?: synchronized(this) {
            instance ?: MusicPlaybackController(context.applicationContext).also { instance = it }
        }
}

/**
 * App-scoped holder for the voice player so a voice note keeps playing while you
 * leave the chat and browse the app. The global
 * voice mini-player binds to this single instance.
 */
object VoicePlayerHolder {
    @Volatile private var instance: VoicePlaybackController? = null
    fun get(context: Context): VoicePlaybackController =
        instance ?: synchronized(this) {
            instance ?: VoicePlaybackController(context.applicationContext).also { instance = it }
        }
}

private const val KEY_SPEED = "voice_speed"
private const val KEY_MUSIC_SPEED = "music_speed"
