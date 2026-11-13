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
    var coverPath by remember { mutableStateOf(coverFile(ctx).takeIf { it.exists() }?.absolutePath ?: "") }
    var videoPath by remember { mutableStateOf("") }
    var avatarSheet by remember { mutableStateOf(false) }
    var videoSource by remember { mutableStateOf<Uri?>(null) }
    // Bumped on every avatar/cover pick so the shared image cache reloads
    // in-place (the file path is reused, so a plain state change wouldn't).
    var mediaVersion by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var customStatus by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(0) }
    var statusSheet by remember { mutableStateOf(false) }
    var nameSheet by remember { mutableStateOf(false) }
    var bioSheet by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (b, c, col) = loadStatus(ctx)
        busy = b; customStatus = c; statusColor = col
    }
    LaunchedEffect(Unit) {
        runCatching { viewModel.repository.getMyProfile() }.getOrNull()?.let { p ->
            if (name.isBlank()) name = p.displayName
            if (bio.isBlank()) bio = p.bio
            if (avatarPath.isBlank()) avatarPath = p.avatarPath
            if (npub.isBlank() && p.npub.isNotBlank()) npub = p.npub
        }
        videoPath = viewModel.repository.myAvatarVideo().takeIf { java.io.File(it).length() > 0 }.orEmpty()
        if (npub.isBlank()) {
            runCatching { viewModel.repository.getIdentityInfo() }.getOrNull()?.npub
                ?.takeIf { it.isNotBlank() }?.let { npub = it }
        }
    }
    LaunchedEffect(state.displayName) {
        if (state.displayName.isNotBlank() && name.isBlank()) name = state.displayName
    }

    fun doSave() {
        if (saving) return
        saving = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val res = runCatching {
                viewModel.repository.setMyProfile(displayName = name, bio = bio, avatarPath = avatarPath)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                saving = false
                snackbarHostState.showSnackbar(
                    if (res.isSuccess) ctx.getString(R.string.profile_saved)
                    else ctx.getString(R.string.profile_save_failed, res.exceptionOrNull()?.message ?: ctx.getString(R.string.common_unknown))
                )
            }
        }
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
    androidx.compose.runtime.LaunchedEffect(croppedAvatar) {
        val picked = croppedAvatar ?: return@LaunchedEffect
        profileLifecycle.withResumed { }
        androidx.compose.runtime.withFrameNanos { }
        croppedAvatar = null
        run {
            picked.let { uri ->
                val path = saveAvatarToInternal(ctx, uri)
                if (path != null) {
                    avatarPath = path
                    mediaVersion++
                    // A new photo replaces a video avatar.
                    val hadVideo = videoPath.isNotBlank()
                    videoPath = ""
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        if (hadVideo) viewModel.repository.setMyAvatarVideo("")
                        runCatching { viewModel.repository.setMyProfile(avatarPath = path) }
                        dropOldAvatarVideos(ctx, keep = emptySet())
                    }
                }
            }
        }
    }
    val coverCrop = rememberLauncherForActivityResult(
        contract = com.canhub.cropper.CropImageContract(),
    ) { result ->
        if (result.isSuccessful) {
            result.uriContent?.let { uri ->
                saveCoverToInternal(ctx, uri)?.let { path ->
                    coverPath = path
                    mediaVersion++
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { viewModel.repository.setMyCover(path) }
                    }
                }
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) videoSource = uri }

    val status: ProfileStatus = if (busy) ProfileStatus.Dnd else ProfileStatus.Online

    Scaffold(
        containerColor = colors.bgPrimary,
        contentWindowInsets = WindowInsets(0),
        topBar = { SelfTopBar(colors, onBack, saving, ::doSave) },
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.navigationBarsPadding()) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            ProfileContent(
                displayName = name,
                avatarPath = avatarPath,
                npub = npub,
                colors = colors,
                coverPath = coverPath,
                videoPath = videoPath,
                status = status,
                statusText = customStatus,
                statusColor = statusColor.takeIf { it != 0 },
                mediaVersion = mediaVersion,
                isSelf = true,
                showAvatarDot = false,
                onChangeCover = { coverCrop.launch(coverCropOptions(ctx)) },
                onChangeAvatar = { avatarSheet = true },
                onSetStatus = { statusSheet = true },
                onEditName = { nameSheet = true },
                bio = bio,
                onEditBio = { bioSheet = true },
            )

            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(R.string.selfprofile_nsec_title), colors)
            val withOwner: (() -> Unit) -> Unit = { action ->
                val act = ctx as? androidx.fragment.app.FragmentActivity
                if (act == null) action()
                else com.alphamessenger.util.DeviceAuth.confirm(act, ctx.getString(R.string.selfprofile_nsec_auth), action)
            }
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

            Spacer(Modifier.height(36.dp))
        }
    }

    if (statusSheet) {
        StatusSheet(
            colors = colors,
            initialBusy = busy,
            initialCustom = customStatus,
            initialColor = statusColor,
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
        AvatarKindSheet(
            colors = colors,
            hasVideo = videoPath.isNotBlank(),
            onDismiss = { avatarSheet = false },
            onPhoto = { pendingPick = "photo"; avatarSheet = false },
            onVideo = { pendingPick = "video"; avatarSheet = false },
            onRemoveVideo = {
                avatarSheet = false
                videoPath = ""
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    viewModel.repository.setMyAvatarVideo("")
                    dropOldAvatarVideos(ctx, keep = setOf(avatarPath))
                }
            },
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
                mediaVersion++
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { viewModel.repository.setMyProfile(avatarPath = cover.absolutePath) }
                    viewModel.repository.setMyAvatarVideo(video.absolutePath)
                    dropOldAvatarVideos(ctx, keep = setOf(video.absolutePath, cover.absolutePath))
                }
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

/** Earlier video avatars and their covers — only the current ones stay. */
private fun dropOldAvatarVideos(ctx: Context, keep: Set<String>) {
    java.io.File(ctx.filesDir, "profile").listFiles()?.forEach { f ->
        val ours = f.name.startsWith("avatar_video_") || f.name.startsWith("avatar_cover_")
        if (ours && f.absolutePath !in keep) f.delete()
    }
}

/** Avatar: a photo, or a short looping video. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvatarKindSheet(
    colors: AlphaColorScheme,
    hasVideo: Boolean,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onRemoveVideo: () -> Unit,
) {
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp)) {
            Text(stringResource(R.string.common_avatar), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            AvatarKindRow(AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.video_avatar_pick_photo), colors.textPrimary, colors, onPhoto)
            AvatarKindRow(AlphaIcons.Outlined.Videocam, stringResource(R.string.video_avatar_pick_video), colors.textPrimary, colors, onVideo)
            if (hasVideo) AvatarKindRow(AlphaIcons.Outlined.DeleteOutline, stringResource(R.string.video_avatar_remove), colors.danger, colors, onRemoveVideo)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AvatarKindRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    colors: AlphaColorScheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(16.dp))
        Text(label, color = tint, fontSize = 15.sp)
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

@Composable
private fun SelfTopBar(colors: AlphaColorScheme, onBack: () -> Unit, saving: Boolean, onSave: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgPrimary)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(AlphaIcons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = colors.textPrimary)
            }
            Text(stringResource(R.string.profile_title), color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .padding(end = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent)
                    .clickable(enabled = !saving, onClick = onSave)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (saving) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = colors.bgPrimary, modifier = Modifier.size(14.dp))
                } else {
                    Text(stringResource(R.string.common_save), color = colors.bgPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSheet(
    colors: AlphaColorScheme,
    initialBusy: Boolean,
    initialCustom: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onSave: (Boolean, String, Int) -> Unit,
) {
    var busy by remember { mutableStateOf(initialBusy) }
    var custom by remember { mutableStateOf(initialCustom) }
    var color by remember { mutableStateOf(initialColor) }
    com.alphamessenger.ui.components.AlphaSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp)) {
            Text(stringResource(R.string.profile_status_title), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it.take(80) },
                placeholder = { Text(stringResource(R.string.profile_status_hint), color = colors.textMuted) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = textFieldColors(colors),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.profile_status_color), color = colors.textMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                for (c in STATUS_COLORS) {
                    val selected = c == color
                    Box(
                        modifier = Modifier
                            .size(if (selected) 34.dp else 28.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color(c))
                            .clickable { color = if (selected) 0 else c },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_dnd), color = colors.textPrimary, fontSize = 15.sp)
                    Text(stringResource(R.string.profile_dnd_desc), color = colors.textMuted, fontSize = 12.sp)
                }
                Switch(checked = busy, onCheckedChange = { busy = it })
            }
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.accent)
                    .clickable { onSave(busy, custom.trim(), color) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.common_save), color = colors.bgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            // One-tap return to plain "Online": clears text, colour and DND.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { onSave(false, "", 0) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.profile_reset_status), color = colors.textMuted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, colors: AlphaColorScheme) {
    Text(
        text,
        color = colors.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp),
    )
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

// --- Cover storage (local, cacheDir/media) ---

private fun coverFile(ctx: Context): java.io.File =
    java.io.File(java.io.File(ctx.cacheDir, "media").apply { mkdirs() }, "cover.jpg")

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
 * Copy + downscale the picked image to `cacheDir/media/cover.jpg` at 3:1
 * (1080×360 center-crop JPEG q85). Local only. Mirrors saveAvatarToInternal.
 */
internal fun saveCoverToInternal(ctx: Context, uri: Uri): String? {
    val target = coverFile(ctx)
    val tmp = java.io.File(target.parentFile, "cover.jpg.tmp")
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
        if (!target.exists() || target.length() <= 0L) null else target.absolutePath
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
