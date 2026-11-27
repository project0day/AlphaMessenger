package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.withResumed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.ui.chat.ProfileContent
import com.alphamessenger.ui.chat.ProfileStatus
import com.alphamessenger.ui.theme.AlphaColorScheme
import com.alphamessenger.ui.theme.AlphaTheme
import kotlinx.coroutines.launch
import com.alphamessenger.R
import androidx.compose.ui.res.stringResource

/**
 * SelfProfileScreen — the SINGLE own-profile screen. Renders the unified
 * [ProfileContent] header (cover + avatar + name + pill key + status) with the
 * self actions (Cover / Avatar / Status), then the editable fields inline
 * (name, bio), the public key and — at the very bottom — the secret access
 * key (nsec). Replaces the old separate edit screen entirely.
 *
 * Cover is a purely LOCAL media file in `cacheDir/media` — not broadcast
 * (there is no cover field in the Rust profile pipeline).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfProfileScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatarPath by remember { mutableStateOf("") }
    var npub by remember { mutableStateOf("") }
    var nsec by remember { mutableStateOf("") }
    var nsecRevealed by remember { mutableStateOf(false) }
    // The key is held only while it is shown: a Kotlin string cannot be wiped,
    // so the reference is dropped as soon as it is not needed.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { nsec = "" } }
    var coverPath by remember { mutableStateOf("") }
    var videoPath by remember { mutableStateOf("") }
    var avatarSheet by remember { mutableStateOf(false) }
    var videoSource by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var customStatus by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(0) }
    var statusSheet by remember { mutableStateOf(false) }
    var nameSheet by remember { mutableStateOf(false) }
    var bioSheet by remember { mutableStateOf(false) }
    var qrSheet by remember { mutableStateOf(false) }
    var keyOpen by remember { mutableStateOf(false) }
    var viewerAt by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        val (b, c, col) = loadStatus(ctx)
        busy = b; customStatus = c; statusColor = col
    }
    LaunchedEffect(Unit) {
        runCatching { viewModel.repository.getMyProfile() }.getOrNull()?.let { p ->
            if (name.isBlank()) name = p.displayName
            if (bio.isBlank()) bio = p.bio
            if (npub.isBlank() && p.npub.isNotBlank()) npub = p.npub
        }
        if (npub.isBlank()) {
            runCatching { viewModel.repository.getIdentityInfo() }.getOrNull()?.npub
                ?.takeIf { it.isNotBlank() }?.let { npub = it }
        }
    }
    // The pictures are the core's: shown as it holds them, and again whenever they change.
    LaunchedEffect(Unit) {
        suspend fun reload() {
            val p = viewModel.repository.myPictures()
            avatarPath = p.photo
            videoPath = p.video.takeIf { java.io.File(it).length() > 0 }.orEmpty()
            coverPath = p.cover.takeIf { java.io.File(it).length() > 0 }.orEmpty()
        }
        reload()
        viewModel.repository.profileChanged.collect { reload() }
    }
    LaunchedEffect(state.displayName) {
        if (state.displayName.isNotBlank() && name.isBlank()) name = state.displayName
    }

    // The cropped photo is applied once this screen is resumed and has drawn a
    // frame: the result arrives before that, and swapping the avatar then (the
    // video avatar's player view leaves with it) fails in the composition.
    var croppedAvatar by remember { mutableStateOf<Uri?>(null) }
    val avatarCrop = rememberLauncherForActivityResult(
        contract = com.canhub.cropper.CropImageContract(),
    ) { result ->
        if (result.isSuccessful) croppedAvatar = result.uriContent
    }
    val profileLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    // One long-lived collector: clearing the pending crop must not cancel the save in flight.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { croppedAvatar }.collect { picked ->
            if (picked == null) return@collect
            profileLifecycle.withResumed { }
            androidx.compose.runtime.withFrameNanos { }
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveOwnPhoto(ctx, picked) }
            croppedAvatar = null
            if (path != null) {
                // A new photo replaces a video avatar.
                avatarPath = path
                videoPath = ""
                viewModel.repository.setMyPhoto(path)
            }
        }
    }
    val coverCrop = rememberLauncherForActivityResult(
        contract = com.canhub.cropper.CropImageContract(),
    ) { result ->
        val uri = result.uriContent
        if (result.isSuccessful && uri != null) scope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveCoverToInternal(ctx, uri) }
            if (path != null) {
                coverPath = path
                viewModel.repository.setMyCoverPicture(path)
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) videoSource = uri }

    val pages = remember(avatarPath, videoPath, coverPath) {
        com.alphamessenger.ui.profile.avatarPages(avatarPath, videoPath, coverPath)
    }
    viewerAt?.let { at ->
        com.alphamessenger.ui.profile.AvatarViewer(
            name, pages, at,
            onDismiss = { viewerAt = null },
            onRemove = { page ->
                when {
                    page.path == coverPath -> { coverPath = ""; viewModel.repository.setMyCoverPicture("") }
                    page.isVideo -> { videoPath = ""; viewModel.repository.removeMyVideoAvatar() }
                    else -> { avatarPath = ""; videoPath = ""; viewModel.repository.removeMyAvatar() }
                }
                if (pages.size <= 1) viewerAt = null
            },
        )
    }
    if (qrSheet) {
        com.alphamessenger.ui.chatlist.ShareNpubSheet(
            npub = npub,
            colors = colors,
            onDismiss = { qrSheet = false },
            loadCode = { viewModel.repository.contactCode() },
        )
    }

    val online = stringResource(R.string.presence_online)
    val dnd = stringResource(R.string.profile_dnd)
    val statusLine = listOf(if (busy) dnd else online, customStatus.trim()).filter { it.isNotBlank() }.joinToString(" · ")

    Scaffold(
        containerColor = colors.bgPrimary,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.navigationBarsPadding()) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            SelfHero(
                colors = colors,
                name = name,
                npub = npub,
                photo = avatarPath,
                video = videoPath,
                cover = coverPath,
                statusLine = statusLine,
                statusDot = ownStatusDot(busy, statusColor),
                onBack = onBack,
                onCover = { coverCrop.launch(coverCropOptions(ctx)) },
                onAvatar = { avatarSheet = true },
                onCopyKey = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("npub", npub))
                    scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.common_copied)) }
                },
            )

            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelfTile(AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.common_avatar), colors, Modifier.weight(1f)) { avatarSheet = true }
                SelfTile(AlphaIcons.Outlined.EmojiEmotions, stringResource(R.string.profile_status_title), colors, Modifier.weight(1f)) { statusSheet = true }
                SelfTile(AlphaIcons.Outlined.QrCode, stringResource(R.string.selfprofile_qr), colors, Modifier.weight(1f)) { qrSheet = true }
                SelfTile(AlphaIcons.Outlined.Share, stringResource(R.string.common_share), colors, Modifier.weight(1f)) {
                    scope.launch {
                        val code = runCatching { viewModel.repository.contactCode() }.getOrDefault(npub)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, code)
                        ctx.startActivity(android.content.Intent.createChooser(send, ctx.getString(R.string.common_share)))
                    }
                }
            }

            SelfSectionTitle(stringResource(R.string.profile_title), colors)
            SelfCard(colors) {
                SelfRow(name.ifBlank { stringResource(R.string.profile_name_question) }, stringResource(R.string.common_name), colors, placeholder = name.isBlank()) { nameSheet = true }
                SelfCardDivider(colors)
                SelfRow(bio.ifBlank { stringResource(R.string.profile_bio_add) }, stringResource(R.string.profile_bio_label), colors, placeholder = bio.isBlank()) { bioSheet = true }
            }

            SelfSectionTitle(stringResource(R.string.selfprofile_section_security), colors)
            val withOwner: (() -> Unit) -> Unit = { action ->
                val act = ctx as? androidx.fragment.app.FragmentActivity
                if (act == null) action()
                else com.alphamessenger.util.DeviceAuth.confirm(act, ctx.getString(R.string.selfprofile_nsec_auth), action)
            }
            SelfCard(colors) {
                SelfRow(
                    stringResource(R.string.selfprofile_nsec_row), stringResource(R.string.selfprofile_nsec_hint), colors,
                    lead = { DangerBadge(AlphaIcons.Outlined.Lock) },
                ) {
                    if (keyOpen) { keyOpen = false; nsecRevealed = false; nsec = "" } else keyOpen = true
                }
            }
            if (keyOpen) {
                Spacer(Modifier.height(10.dp))
                SecretKeyBlock(
                revealed = nsecRevealed,
                value = nsec,
                colors = colors,
                // The key is the account: showing or copying it asks who is holding the phone.
                onToggle = {
                    if (nsecRevealed) { nsecRevealed = false; nsec = "" }
                    else withOwner {
                        if (nsec.isBlank()) {
                            scope.launch {
                                nsec = runCatching { viewModel.repository.exportNsec() }.getOrDefault("")
                            }
                        }
                        nsecRevealed = true
                    }
                },
                onCopy = {
                    withOwner { scope.launch {
                        val key = nsec.ifBlank {
                            runCatching { viewModel.repository.exportNsec() }.getOrDefault("")
                        }
                        if (key.isNotBlank()) {
                            com.alphamessenger.util.SecretClipboard.copy(ctx, "nsec", key)
                            snackbarHostState.showSnackbar(ctx.getString(R.string.selfprofile_nsec_copied))
                        }
                    } }
                },
                )
            }

            Spacer(Modifier.height(36.dp))
        }
    }

    if (statusSheet) {
        OwnStatusSheet(
            colors = colors,
            initialBusy = busy,
            initialCustom = customStatus,
            initialColor = statusColor,
            palette = STATUS_COLORS,
            onDismiss = { statusSheet = false },
            onSave = { newBusy, newCustom, newColor ->
                busy = newBusy
                customStatus = newCustom
                statusColor = newColor
                saveStatus(ctx, newBusy, newCustom, newColor)
                viewModel.repository.presenceSetMyStatus(
                    newBusy, newCustom, newColor.toLong() and 0xFFFFFFFFL,
                )
                // Publish the change straight away instead of waiting for the
                // ~15s sweep tick.
                scope.launch { runCatching { viewModel.repository.presenceTick() } }
                statusSheet = false
            },
        )
    }

    // The picker opens once the sheet is gone: an activity started while the
    // sheet is still being removed leaves that removal half-applied, and the
    // composition fails on it when we come back.
    var pendingPick by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(pendingPick, avatarSheet) {
        val pick = pendingPick ?: return@LaunchedEffect
        if (avatarSheet) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        pendingPick = null
        when (pick) {
            "photo" -> avatarCrop.launch(avatarCropOptions(ctx))
            "video" -> videoPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly,
                ),
            )
        }
    }

    if (avatarSheet) {
        val hasAvatar = avatarPath.isNotBlank()
        OwnAvatarSheet(
            colors = colors,
            name = name,
            seed = npub,
            photo = avatarPath,
            video = videoPath,
            onDismiss = { avatarSheet = false },
            onOpen = if (hasAvatar) ({ avatarSheet = false; viewerAt = 0 }) else null,
            onPhoto = { pendingPick = "photo"; avatarSheet = false },
            onVideo = { pendingPick = "video"; avatarSheet = false },
            onRemoveVideo = if (videoPath.isNotBlank()) ({
                avatarSheet = false
                videoPath = ""
                viewModel.repository.removeMyVideoAvatar()
            }) else null,
            onRemove = if (hasAvatar) ({
                avatarSheet = false
                avatarPath = ""
                videoPath = ""
                viewModel.repository.removeMyAvatar()
            }) else null,
        )
    }

    videoSource?.let { src ->
        com.alphamessenger.ui.profile.VideoAvatarEditor(
            source = src,
            colors = colors,
            onDismiss = { videoSource = null },
            onDone = { video, cover ->
                videoSource = null
                avatarPath = cover.absolutePath
                videoPath = video.absolutePath
                viewModel.repository.setMyVideoAvatar(video.absolutePath, cover.absolutePath)
            },
        )
    }

    if (bioSheet) {
        NameSheet(
            colors = colors,
            initial = bio,
            title = stringResource(R.string.profile_bio_label),
            hint = stringResource(R.string.profile_bio_add),
            maxLength = 255,
            singleLine = false,
            onDismiss = { bioSheet = false },
            onSave = { newBio ->
                bio = newBio
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { viewModel.repository.setMyProfile(displayName = name, bio = newBio) }
                }
                bioSheet = false
            },
        )
    }

    if (nameSheet) {
        NameSheet(
            colors = colors,
            initial = name,
            onDismiss = { nameSheet = false },
            onSave = { newName ->
                name = newName
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { viewModel.repository.setMyProfile(displayName = newName, bio = bio) }
                }
                nameSheet = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameSheet(
    colors: AlphaColorScheme,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    title: String = stringResource(R.string.common_name),
    hint: String = stringResource(R.string.profile_name_question),
    maxLength: Int = 64,
    singleLine: Boolean = true,
) {
    var value by remember { mutableStateOf(initial) }
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(maxLength) },
                placeholder = { Text(hint, color = colors.textMuted) },
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 6,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors(colors),
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.accent)
                    .clickable { onSave(value.trim()) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.common_save), color = colors.bgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Preset indicator colours (ARGB ints) offered in the status sheet. Green is
 * intentionally omitted — it is reserved for the REAL "online" state, so a
 * custom colour never masquerades as a genuine online indicator. Leaving the
 * choice empty keeps the accurate presence colour.
 */
private val STATUS_COLORS = listOf(
    0xFFF0B429.toInt(), // amber
    0xFFE5484D.toInt(), // red
    0xFF5B7CFA.toInt(), // blue
    0xFFAA6BFF.toInt(), // purple
    0xFF19C3D1.toInt(), // cyan
    0xFFFF6BAA.toInt(), // pink
    0xFFFF8A3D.toInt(), // orange
    0xFFEDEFF2.toInt(), // white
)

private fun avatarCropOptions(ctx: Context) = com.canhub.cropper.CropImageContractOptions(
    uri = null,
    cropImageOptions = com.canhub.cropper.CropImageOptions(
        imageSourceIncludeCamera = false,
        imageSourceIncludeGallery = true,
        aspectRatioX = 1, aspectRatioY = 1, fixAspectRatio = true,
        cropShape = com.canhub.cropper.CropImageView.CropShape.OVAL,
        outputCompressFormat = android.graphics.Bitmap.CompressFormat.JPEG,
        outputCompressQuality = 92,
        outputRequestWidth = 1024, outputRequestHeight = 1024,
        autoZoomEnabled = true, showProgressBar = true,
        activityTitle = ctx.getString(R.string.common_avatar),
        toolbarColor = android.graphics.Color.BLACK,
        toolbarBackButtonColor = android.graphics.Color.WHITE,
        toolbarTintColor = android.graphics.Color.WHITE,
        activityMenuIconColor = android.graphics.Color.WHITE,
        activityMenuTextColor = android.graphics.Color.WHITE,
        activityBackgroundColor = android.graphics.Color.BLACK,
        cropMenuCropButtonTitle = ctx.getString(R.string.common_done),
    ),
)

private fun coverCropOptions(ctx: Context) = com.canhub.cropper.CropImageContractOptions(
    uri = null,
    cropImageOptions = com.canhub.cropper.CropImageOptions(
        imageSourceIncludeCamera = false,
        imageSourceIncludeGallery = true,
        aspectRatioX = 3, aspectRatioY = 1, fixAspectRatio = true,
        outputCompressFormat = android.graphics.Bitmap.CompressFormat.JPEG,
        outputCompressQuality = 90,
        outputRequestWidth = 1600, outputRequestHeight = 533,
        autoZoomEnabled = true, showProgressBar = true,
        activityTitle = ctx.getString(R.string.common_cover),
        toolbarColor = android.graphics.Color.BLACK,
        toolbarBackButtonColor = android.graphics.Color.WHITE,
        toolbarTintColor = android.graphics.Color.WHITE,
        activityMenuIconColor = android.graphics.Color.WHITE,
        activityMenuTextColor = android.graphics.Color.WHITE,
        activityBackgroundColor = android.graphics.Color.BLACK,
        cropMenuCropButtonTitle = ctx.getString(R.string.common_done),
    ),
)

/**
 * Copy + downscale the picked image to a 3:1 center-crop JPEG stored under its
 * content name (see [com.alphamessenger.data.OwnPictureFiles]).
 */
internal fun saveCoverToInternal(ctx: Context, uri: Uri): String? {
    val files = com.alphamessenger.data.OwnPictureFiles
    val target = files.scratch(ctx, files.COVER)
    val tmp = java.io.File(target.parentFile, "${target.name}.tmp")
    return try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(tmp).use { out -> input.copyTo(out) }
        } ?: return null
        if (!tmp.exists() || tmp.length() <= 0L) return null

        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        com.alphamessenger.data.MediaVault.decodeFile(tmp.absolutePath, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        // HD cover 3:1 → 1600×533 q88 (~200-700 KB, under the ~900 KB cap).
        val targetW = 1600
        val targetH = 533
        var sample = 1
        while ((srcW / (sample * 2)) >= targetW && (srcH / (sample * 2)) >= targetH) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = com.alphamessenger.data.MediaVault.decodeFile(tmp.absolutePath, opts) ?: return null

        val wantW: Int
        val wantH: Int
        if (raw.width * targetH >= raw.height * targetW) {
            wantH = raw.height
            wantW = raw.height * targetW / targetH
        } else {
            wantW = raw.width
            wantH = raw.width * targetH / targetW
        }
        val cropX = (raw.width - wantW) / 2
        val cropY = (raw.height - wantH) / 2
        val cropped = android.graphics.Bitmap.createBitmap(raw, cropX, cropY, wantW, wantH)
        if (cropped !== raw) raw.recycle()
        val scaled = android.graphics.Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        if (scaled !== cropped) cropped.recycle()

        java.io.FileOutputStream(target).use { out ->
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
        }
        scaled.recycle()
        if (!target.exists() || target.length() <= 0L) null else files.adopt(ctx, target, files.COVER)
    } catch (t: Throwable) {
        null
    } finally {
        runCatching { if (tmp.exists()) tmp.delete() }
    }
}

// --- Manual status persistence (local metadata) ---

private const val STATUS_PREFS = "profile_status"

internal fun loadStatus(ctx: Context): Triple<Boolean, String, Int> {
    val p = ctx.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
    return Triple(
        p.getBoolean("busy", false),
        p.getString("custom", "") ?: "",
        p.getInt("color", 0),
    )
}

internal fun saveStatus(ctx: Context, busy: Boolean, custom: String, color: Int) {
    ctx.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean("busy", busy)
        .putString("custom", custom)
        .putInt("color", color)
        .apply()
}
