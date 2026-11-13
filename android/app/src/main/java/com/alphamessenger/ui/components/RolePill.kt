package com.alphamessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R

/** Sender ids of a chat's admins, for their badge in comments. */
val LocalAdminSenders = androidx.compose.runtime.staticCompositionLocalOf<Set<String>> { emptySet() }

private fun glyph(name: String, d: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black), pathBuilder = d).build()

private val Crown = glyph("crown") {
    moveTo(3f, 7f); lineTo(7.5f, 11f); lineTo(12f, 4f); lineTo(16.5f, 11f); lineTo(21f, 7f); lineTo(19f, 19f); lineTo(5f, 19f); close()
}
private val Shield = glyph("shield") {
    moveTo(12f, 2f); lineTo(20f, 5f); lineTo(20f, 11f)
    curveTo(20f, 16f, 16.6f, 20.4f, 12f, 22f)
    curveTo(7.4f, 20.4f, 4f, 16f, 4f, 11f); lineTo(4f, 5f); close()
}
private val Gavel = glyph("gavel") {
    moveTo(13.8f, 2.6f); lineTo(21.4f, 10.2f); lineTo(18.6f, 13f); lineTo(17.2f, 11.6f); lineTo(13.7f, 15.1f)
    lineTo(19.7f, 21.1f); lineTo(18.3f, 22.5f); lineTo(12.3f, 16.5f); lineTo(8.8f, 20f); lineTo(10.2f, 21.4f)
    lineTo(7.4f, 24.2f); lineTo(0.8f, 16.6f); lineTo(3.6f, 13.8f); lineTo(5f, 15.2f); lineTo(13.5f, 6.7f); lineTo(12.1f, 5.3f); close()
}

private data class RoleLook(val icon: ImageVector, val color: Color, val label: Int)

private fun lookOf(role: String): RoleLook? = when (role) {
    "owner" -> RoleLook(Crown, Color(0xFFE8B44A), R.string.role_owner)
    "admin" -> RoleLook(Shield, Color(0xFF4FA8F5), R.string.role_admin)
    "moderator" -> RoleLook(Gavel, Color(0xFF9B7CF2), R.string.role_moderator)
    else -> null
}

/** A role next to a name: icon and word on a soft tint. Members get nothing. */
@Composable
fun RolePill(role: String, modifier: Modifier = Modifier) {
    val look = lookOf(role) ?: return
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(look.color.copy(alpha = 0.16f))
            .padding(start = 5.dp, end = 7.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(look.icon, null, tint = look.color, modifier = Modifier.size(11.dp))
        Text(stringResource(look.label), color = look.color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
