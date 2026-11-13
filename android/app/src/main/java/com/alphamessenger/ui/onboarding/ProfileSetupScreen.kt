package com.alphamessenger.ui.onboarding

import com.alphamessenger.ui.icons.AlphaIcons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.theme.AlphaTheme

private const val NAME_MAX = 32

/** Step 4 of 5: name and photo — optional, changeable any time. */
@Composable
fun ProfileSetupScreen(
    viewModel: OnboardingViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val cs = AlphaTheme.colorScheme
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }

    // Decoded off the main thread, downsampled to the avatar size.
    val avatarBitmap: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, uiState.avatarUri) {
        val uri = uiState.avatarUri
        value = if (uri == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { viewModel.updateAvatarUri(it) }
    }
    val pickPhoto = { avatarLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    OnbScreen(
        title = stringResource(R.string.onb_profile_title),
        subtitle = stringResource(R.string.onb_profile_sub),
        step = 4,
        onBack = onBack,
        bottom = {
            OnbPrimaryButton(
                stringResource(R.string.common_continue),
                { saving = true; viewModel.saveProfileAndNavigate { saving = false; onContinue() } },
                enabled = uiState.displayName.isNotBlank(),
                loading = saving,
            )
            OnbTextButton(stringResource(R.string.common_skip), onContinue, Modifier.align(Alignment.CenterHorizontally))
        },
    ) {
        Box(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)) {
            Box(
                Modifier.size(112.dp).clip(CircleShape).background(cs.surface2).clickable(onClick = pickPhoto),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = avatarBitmap
                if (bmp != null) {
                    Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.size(112.dp).clip(CircleShape))
                } else {
                    Icon(AlphaIcons.Outlined.PersonOutline, null, tint = cs.textMuted, modifier = Modifier.size(48.dp))
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(3.dp, cs.bgPrimary, CircleShape)
                    .background(cs.accent)
                    .clickable(onClick = pickPhoto),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AlphaIcons.Outlined.PhotoCamera, stringResource(R.string.onb_profile_pick_photo), tint = cs.onAccent(), modifier = Modifier.size(20.dp))
            }
        }
        OnbField(
            value = uiState.displayName,
            onValueChange = { viewModel.updateDisplayName(it.take(NAME_MAX)) },
            label = stringResource(R.string.onb_profile_name),
            trailing = {
                Text(
                    (NAME_MAX - uiState.displayName.length).toString(),
                    color = cs.textMuted, fontSize = 13.sp,
                    modifier = Modifier.offset(x = (-10).dp),
                )
            },
        )
    }
}
