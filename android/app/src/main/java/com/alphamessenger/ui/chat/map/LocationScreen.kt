package com.alphamessenger.ui.chat.map

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alphamessenger.R
import com.alphamessenger.ui.chat.openNavigation
import com.alphamessenger.ui.chat.shareLocation
import com.alphamessenger.ui.theme.AlphaTheme

/** Full-screen location: the map edge to edge, floating controls, place card. */
@Composable
fun LocationScreen(
    lat: Double,
    lng: Double,
    name: String,
    address: String,
    onDismiss: () -> Unit,
) {
    val colors = AlphaTheme.colorScheme
    val ctx = LocalContext.current
    val dark = colors.bgPrimary.luminance() < 0.5f
    val controller = remember { AlphaMapController() }
    var menuOpen by remember { mutableStateOf(false) }
    val coords = "%.6f, %.6f".format(java.util.Locale.US, lat, lng)
    val title = name.ifBlank { stringResource(R.string.location_fallback) }
    val copied = stringResource(R.string.location_coords_copied)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
    ) {
        FullscreenDialogWindow(darkIcons = !dark)
        Box(Modifier.fillMaxSize().background(colors.bgPrimary)) {
            AlphaMapView(lat = lat, lng = lng, dark = dark, controller = controller, modifier = Modifier.fillMaxSize())

            // Top: back + menu, floating over the map.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MapRoundButton(AlphaIcons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                Box {
                    MapRoundButton(AlphaIcons.Filled.MoreVert, stringResource(R.string.common_more)) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.location_route)) },
                            onClick = { menuOpen = false; openNavigation(ctx, lat, lng, name) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            onClick = { menuOpen = false; shareLocation(ctx, lat, lng, name) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.location_copy_coords)) },
                            onClick = {
                                menuOpen = false
                                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("location", coords))
                                android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }

            // Bottom: recenter + place card.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                MapRoundButton(AlphaIcons.Filled.GpsFixed, stringResource(R.string.location_recenter)) {
                    controller.moveTo(lat, lng)
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.bgSecondary)
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AlphaIcons.Filled.LocationOn, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (address.isNotBlank()) {
                        Text(address, color = colors.textSecondary, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(coords, color = colors.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.accent)
                            .clickable { openNavigation(ctx, lat, lng, name) }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AlphaIcons.Filled.Directions, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.location_route), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MapRoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = AlphaTheme.colorScheme
    Box(
        Modifier.size(44.dp).shadow(6.dp, CircleShape).clip(CircleShape)
            .background(colors.bgSecondary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = colors.textPrimary, modifier = Modifier.size(22.dp))
    }
}
