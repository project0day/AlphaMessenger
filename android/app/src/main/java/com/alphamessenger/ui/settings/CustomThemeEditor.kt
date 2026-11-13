package com.alphamessenger.ui.settings

import com.alphamessenger.ui.icons.AlphaIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionChoiceRow
import com.alphamessenger.ui.components.OptionDivider
import com.alphamessenger.ui.components.OptionGroup
import com.alphamessenger.ui.components.OptionNote
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.components.OptionSection
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.ChatWallpaper
import com.alphamessenger.ui.theme.CustomTheme

private val ACCENTS = listOf(0xFF3D9DF0, 0xFF7C5CF0, 0xFFE0507A, 0xFFEF8A3A, 0xFF2FB37A, 0xFF14B8C9, 0xFFE0A21F, 0xFF9AA1AB).map { Color(it) }
private val BUBBLES = listOf(0xFF2F7FE0, 0xFF7B5CF0, 0xFFD9467A, 0xFFEA7A2F, 0xFF1F9D6A, 0xFF0FA3B5, 0xFF3A3F47, 0xFFF2F4F7).map { Color(it) }
private val INCOMING_DARK = listOf(0xFF2A2D33, 0xFF1F2A3A, 0xFF2C2240, 0xFF1D3029, 0xFF3A2A22).map { Color(it) }
private val INCOMING_LIGHT = listOf(0xFFFFFFFF, 0xFFEEF3FB, 0xFFF3EEFC, 0xFFEAF6F0, 0xFFFBF1E9).map { Color(it) }
private val BG_DARK = listOf(0xFF121212, 0xFF212121, 0xFF0F1626, 0xFF1A1033, 0xFF0E1F18, 0xFF2A1515).map { Color(it) }
private val BG_LIGHT = listOf(0xFFFFFFFF, 0xFFF4F5F7, 0xFFEEF3FB, 0xFFF6F0FF, 0xFFEFF8F2, 0xFFFFF5EA).map { Color(it) }
private val PANELS_DARK = listOf(0xFF000000, 0xFF181818, 0xFF1C2333, 0xFF231A3A, 0xFF16261F).map { Color(it) }
private val PANELS_LIGHT = listOf(0xFFFFFFFF, 0xFFF0F1F4, 0xFFE6EEFA, 0xFFEFE8FB, 0xFFE6F3EB).map { Color(it) }
private val TICKS = listOf(0xFF7FD3FF, 0xFF5EF0B0, 0xFFFFFFFF, 0xFFFFD166, 0xFF23A55A).map { Color(it) }

/** The localized name of a ready starting point. */
@Composable
internal fun presetName(t: CustomTheme): String = when (t.id) {
    "p-ocean" -> stringResource(R.string.theme_preset_ocean)
    "p-sunset" -> stringResource(R.string.theme_preset_sunset)
    "p-mint" -> stringResource(R.string.theme_preset_mint)
    "p-graphite" -> stringResource(R.string.theme_preset_graphite)
    "p-lavender" -> stringResource(R.string.theme_preset_lavender)
    "p-sand" -> stringResource(R.string.theme_preset_sand)
    else -> t.name
}

/**
 * "Own theme": a few picks with the chat preview live above them. Every other
 * colour is derived ([CustomTheme.scheme]); text stays readable by itself.
 */
@Composable
fun CustomThemeEditor(
    start: CustomTheme,
    isNew: Boolean,
    wallpaper: ChatWallpaper,
    msgFontFactor: Float,
    onBack: () -> Unit,
    onSave: (CustomTheme) -> Unit,
    onDelete: (() -> Unit)?,
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val colors = AlphaTheme.colorScheme
    var t by remember { mutableStateOf(start) }
    var name by remember { mutableStateOf(start.name) }
    // Which colour the picker is choosing: the setter for it.
    var picking by remember { mutableStateOf<Pair<Color, (Color) -> Unit>?>(null) }
    val scheme = remember(t) { t.scheme() }
    val defaultName = stringResource(R.string.theme_my_default_name)

    OptionScreen(title = stringResource(R.string.theme_editor_title), onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        LiveChatPreview(scheme = scheme, wallpaper = wallpaper, msgFontFactor = msgFontFactor)
        if (t.readability().first < 3f) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.theme_editor_bad_out), color = colors.warning, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        }

        // Ready starting points.
        OptionSection(stringResource(R.string.theme_editor_presets))
        val presetLabels = CustomTheme.PRESETS.map { presetName(it) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CustomTheme.PRESETS.forEach { p ->
                val label = presetName(p)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable {
                    t = p.copy(id = t.id, name = t.name)
                    if (name.isBlank() || name in presetLabels || name == defaultName) name = label
                }) {
                    Box(
                        Modifier.size(72.dp, 44.dp).clip(RoundedCornerShape(14.dp))
                            .background(p.scheme().bgPrimary)
                            .padding(8.dp),
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        Box(Modifier.size(34.dp, 14.dp).clip(RoundedCornerShape(7.dp)).background(Brush.linearGradient(listOf(p.out1, if (p.gradient) p.out2 else p.out1))))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        OptionSection(stringResource(R.string.theme_editor_base))
        OptionGroup {
            OptionChoiceRow(stringResource(R.string.theme_base_dark), selected = !t.light, onClick = { t = t.copy(light = false, incoming = null) })
            OptionDivider()
            OptionChoiceRow(stringResource(R.string.theme_base_light), selected = t.light, onClick = { t = t.copy(light = true, incoming = null) })
            OptionDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.theme_editor_tint), color = colors.textPrimary, fontSize = 15.sp)
                com.alphamessenger.ui.components.AlphaSlider(
                    value = t.tint.toFloat(), onValueChange = { t = t.copy(tint = it.toInt()) }, valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent),
                )
            }
        }

        // Own background: one colour or a top-to-bottom gradient, and the panels.
        OptionSection(stringResource(R.string.theme_editor_bg))
        OptionGroup {
            OptionChoiceRow(stringResource(R.string.theme_out_solid), selected = t.background2 == null, onClick = { t = t.copy(background2 = null) })
            OptionDivider()
            OptionChoiceRow(stringResource(R.string.theme_out_gradient), selected = t.background2 != null, onClick = {
                t = t.copy(background = t.background ?: scheme.bgPrimary, background2 = t.background2 ?: scheme.accent.copy(alpha = 1f).let { androidx.compose.ui.graphics.lerp(scheme.bgPrimary, it, 0.35f) })
            })
            OptionDivider()
            Swatches(if (t.isLight) BG_LIGHT else BG_DARK, t.background, auto = true,
                onPick = { t = t.copy(background = it, background2 = if (it == null) null else t.background2) },
                onCustom = { picking = (t.background ?: scheme.bgPrimary) to { c -> t = t.copy(background = c) } })
            if (t.background2 != null) {
                Text(stringResource(R.string.theme_editor_out2), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
                Swatches(if (t.isLight) BG_LIGHT else BG_DARK, t.background2, auto = false,
                    onPick = { t = t.copy(background2 = it) },
                    onCustom = { picking = (t.background2 ?: scheme.bgPrimary) to { c -> t = t.copy(background2 = c) } })
            }
        }
        OptionNote(stringResource(R.string.theme_editor_bg_hint))

        OptionSection(stringResource(R.string.theme_editor_panels))
        OptionGroup {
            Swatches(if (t.isLight) PANELS_LIGHT else PANELS_DARK, t.panels, auto = true,
                onPick = { t = t.copy(panels = it) },
                onCustom = { picking = (t.panels ?: scheme.bgSecondary) to { c -> t = t.copy(panels = c) } })
        }

        OptionSection(stringResource(R.string.theme_editor_accent))
        OptionGroup {
            Swatches(ACCENTS, t.accent, auto = false, onPick = { t = t.copy(accent = it!!) }, onCustom = { picking = t.accent to { c -> t = t.copy(accent = c) } })
        }
        OptionNote(stringResource(R.string.theme_editor_accent_hint))

        OptionSection(stringResource(R.string.theme_editor_out))
        OptionGroup {
            OptionChoiceRow(stringResource(R.string.theme_out_solid), selected = !t.gradient, onClick = { t = t.copy(gradient = false) })
            OptionDivider()
            OptionChoiceRow(stringResource(R.string.theme_out_gradient), selected = t.gradient, onClick = { t = t.copy(gradient = true) })
            OptionDivider()
            Swatches(BUBBLES, t.out1, auto = false, onPick = { t = t.copy(out1 = it!!) }, onCustom = { picking = t.out1 to { c -> t = t.copy(out1 = c) } })
            if (t.gradient) {
                Text(stringResource(R.string.theme_editor_out2), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
                Swatches(BUBBLES, t.out2, auto = false, onPick = { t = t.copy(out2 = it!!) }, onCustom = { picking = t.out2 to { c -> t = t.copy(out2 = c) } })
            }
        }

        OptionSection(stringResource(R.string.theme_editor_in))
        OptionGroup {
            Swatches(if (t.light) INCOMING_LIGHT else INCOMING_DARK, t.incoming, auto = true,
                onPick = { t = t.copy(incoming = it) }, onCustom = { picking = (t.incoming ?: scheme.msgIn) to { c -> t = t.copy(incoming = c) } })
        }

        OptionSection(stringResource(R.string.theme_editor_tick))
        OptionGroup {
            Swatches(TICKS, t.tick, auto = true, onPick = { t = t.copy(tick = it) }, onCustom = { picking = (t.tick ?: scheme.success) to { c -> t = t.copy(tick = c) } })
        }

        OptionSection(stringResource(R.string.theme_editor_name))
        OutlinedTextField(
            value = name, onValueChange = { name = it.replace("\n", " ").replace(";", " ").take(32) },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            placeholder = { Text(defaultName, color = colors.textMuted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent, unfocusedBorderColor = colors.borderLight, cursorColor = colors.accent,
                focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        val done = t.copy(name = name.trim().ifBlank { defaultName })
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(colors.accent)
                .clickable { onSave(done) },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.theme_editor_save), color = com.alphamessenger.ui.theme.readableOn(colors.accent), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        if (!isNew) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, done.link())
                ctx.startActivity(android.content.Intent.createChooser(send, null))
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.theme_editor_share), color = colors.accent) }
            onDelete?.let { del ->
                TextButton(onClick = del, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.theme_editor_delete), color = colors.danger) }
            }
        }
    }

    picking?.let { (initial, set) ->
        ColorPickerDialog(initial, onDismiss = { picking = null }, onPick = { set(it); picking = null })
    }
}

/** A row of colour dots: optional "auto", the palette, and "+" for any colour. */
@Composable
private fun Swatches(list: List<Color>, selected: Color?, auto: Boolean, onPick: (Color?) -> Unit, onCustom: () -> Unit) {
    val colors = AlphaTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (auto) Dot(
            Brush.sweepGradient(listOf(Color(0xFF3D9DF0), Color(0xFF7C5CF0), Color(0xFFE0507A), Color(0xFFEF8A3A), Color(0xFF2FB37A), Color(0xFF3D9DF0))),
            selected == null, stringResource(R.string.theme_auto),
        ) { onPick(null) }
        list.forEach { c -> Dot(androidx.compose.ui.graphics.SolidColor(c), selected == c, null) { onPick(c) } }
        val custom = selected != null && selected !in list
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(if (custom) selected!! else colors.bgTertiary)
                .border(2.dp, if (custom) colors.textPrimary else Color.Transparent, CircleShape)
                .clickable(onClick = onCustom),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AlphaIcons.Outlined.Add, stringResource(R.string.theme_pick_color),
                tint = if (custom) com.alphamessenger.ui.theme.readableOn(selected!!) else colors.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Dot(brush: Brush, on: Boolean, label: String?, onClick: () -> Unit) {
    val colors = AlphaTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(brush)
            .border(1.dp, colors.alphaBorder, CircleShape)
            .border(if (on) 2.dp else 0.dp, if (on) colors.textPrimary else Color.Transparent, CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(AlphaIcons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** Any colour: hue, saturation and brightness, with the result shown large. */
@Composable
private fun ColorPickerDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val colors = AlphaTheme.colorScheme
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    // A typed code is kept exactly; moving a slider goes back to the sliders' colour.
    var exact by remember { mutableStateOf<Color?>(null) }
    val c = exact ?: Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    // Typed code, e.g. #212121: follows the sliders, and a full code moves them.
    var hex by remember { mutableStateOf("#%06X".format(c.toArgb() and 0xFFFFFF)) }
    androidx.compose.runtime.LaunchedEffect(c) {
        val shown = "#%06X".format(c.toArgb() and 0xFFFFFF)
        if (hex.length == 7 && !hex.equals(shown, ignoreCase = true)) hex = shown
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSecondary,
        title = { Text(stringResource(R.string.theme_pick_color), color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(c), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = hex,
                        onValueChange = { raw ->
                            val digits = raw.removePrefix("#").filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6).uppercase()
                            hex = "#" + digits
                            if (digits.length == 6) {
                                val out = FloatArray(3)
                                android.graphics.Color.colorToHSV(digits.toInt(16) or 0xFF000000.toInt(), out)
                                h = out[0]; s = out[1]; v = out[2]
                                exact = Color(digits.toInt(16) or 0xFF000000.toInt())
                            }
                        },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                            autoCorrect = false,
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = com.alphamessenger.ui.theme.readableOn(c), fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(com.alphamessenger.ui.theme.readableOn(c)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.theme_hex_hint), color = colors.textMuted, fontSize = 11.sp)
                GradientSlider(stringResource(R.string.theme_hue), h / 360f, (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }) { h = it * 360f; exact = null }
                GradientSlider(stringResource(R.string.theme_sat), s,
                    listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 0f, v))), Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v))))) { s = it; exact = null }
                GradientSlider(stringResource(R.string.theme_val), v,
                    listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, 1f))))) { v = it; exact = null }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(c) }) { Text(stringResource(R.string.common_done), color = colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = colors.textMuted) } },
    )
}

/** A slider whose track is the range it chooses from. */
@Composable
private fun GradientSlider(label: String, value: Float, track: List<Color>, onChange: (Float) -> Unit) {
    val colors = AlphaTheme.colorScheme
    Column {
        Text(label, color = colors.textSecondary, fontSize = 12.sp)
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(track)))
            com.alphamessenger.ui.components.AlphaSlider(
                value = value, onValueChange = onChange,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                ),
            )
        }
    }
}
