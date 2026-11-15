package com.alphamessenger.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphamessenger.R
import com.alphamessenger.ui.components.OptionScreen
import com.alphamessenger.ui.stories.SliderBar
import com.alphamessenger.ui.theme.AlphaTheme
import com.alphamessenger.ui.theme.PATTERN_RECIPES
import com.alphamessenger.ui.theme.PatternSpec
import com.alphamessenger.ui.theme.PatternWallpaper

/**
 * Appearance → Chat background → a pattern: one of Alpha's or one kept before,
 * its colours, a fresh shuffle, and how it sits under the bubbles (sharp to
 * misty, faint to bright, small to large, thin to bold).
 */
@Composable
fun PatternEditorScreen(start: PatternSpec?, startRecipe: Int, onBack: () -> Unit, onSave: (PatternSpec) -> Unit) {
    val cs = AlphaTheme.colorScheme
    // -1: the pattern came whole (kept or offered), so there is no recipe to rebuild it from.
    var recipe by remember { mutableIntStateOf(if (start != null) -1 else startRecipe.coerceIn(0, PATTERN_RECIPES.lastIndex)) }
    var palette by remember { mutableIntStateOf(0) }
    var seed by remember { mutableLongStateOf(-1L) }
    val base0 = start ?: PATTERN_RECIPES[recipe.coerceAtLeast(0)].spec(0)
    var blur by remember { mutableFloatStateOf(base0.blur) }
    var opacity by remember { mutableFloatStateOf(base0.opacity) }
    var scale by remember { mutableFloatStateOf(base0.scale) }
    var stroke by remember { mutableFloatStateOf(base0.stroke) }
    var dark by remember { mutableStateOf(cs.bgPrimary.luminance() < 0.5f) }

    var own by remember { mutableStateOf(false) }
    fun pick(i: Int) {
        recipe = i; palette = 0; seed = -1L; own = false
        val s = PATTERN_RECIPES[i].spec(0)
        opacity = s.opacity; stroke = s.stroke
    }
    val base = remember(recipe, palette, seed) {
        if (recipe < 0) start!! else PATTERN_RECIPES[recipe].let { r -> if (seed < 0) r.spec(palette) else r.spec(palette, seed) }
    }
    // Own colours: background (one, or two as a gradient) and figures, for the side being previewed.
    val lightBg = remember { androidx.compose.runtime.mutableStateListOf<Color>() }
    val darkBg = remember { androidx.compose.runtime.mutableStateListOf<Color>() }
    var lightInk by remember { mutableStateOf(Color.Black) }
    var darkInk by remember { mutableStateOf(Color.White) }
    var degrees by remember { mutableFloatStateOf(180f) }
    // 0 = background, 1 = its second colour, 2 = figures.
    var target by remember { mutableIntStateOf(0) }
    fun startOwn() {
        lightBg.clear(); lightBg.addAll(base.lightBg.take(2)); darkBg.clear(); darkBg.addAll(base.darkBg.take(2))
        lightInk = base.lightInk; darkInk = base.darkInk; degrees = base.degrees; target = 0; own = true
    }
    val bgs = if (dark) darkBg else lightBg
    fun colorOf(t: Int): Color = when (t) { 2 -> if (dark) darkInk else lightInk; else -> bgs.getOrElse(t) { bgs.first() } }
    fun put(c: Color) {
        when {
            target == 2 -> if (dark) darkInk = c else lightInk = c
            target < bgs.size -> bgs[target] = c
        }
    }
    val hsv = remember { Hsv.of(Color.Black) }
    androidx.compose.runtime.LaunchedEffect(own, target, dark) { if (own) hsv.set(colorOf(target)) }

    val spec = base.copy(blur = blur, opacity = opacity, scale = scale, stroke = stroke).let {
        if (!own) it else it.copy(lightBg = lightBg.toList(), lightInk = lightInk, darkBg = darkBg.toList(), darkInk = darkInk, degrees = degrees)
    }

    OptionScreen(stringResource(R.string.wp_pattern_title), onBack = onBack) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.bgSecondary).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(false to R.string.wp_pattern_light, true to R.string.wp_pattern_dark).forEach { (d, label) ->
                    Box(
                        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (dark == d) cs.bgTertiary else Color.Transparent).clickable { dark = d },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(label), color = cs.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(20.dp))) {
                PatternWallpaper(spec, Modifier.fillMaxSize(), dark = dark)
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                    Text(
                        stringResource(R.string.wp_preview_in), color = cs.textPrimary, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)).background(cs.msgIn)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Text(
                        stringResource(R.string.wp_preview_out), color = cs.msgOutText, fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                            .background(Brush.linearGradient(listOf(cs.msgOutStart, cs.msgOutEnd)))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            // Alpha's patterns.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PATTERN_RECIPES.forEachIndexed { i, r ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable { pick(i) }) {
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
                                .border(if (recipe == i) 2.dp else 1.dp, if (recipe == i) cs.accent else cs.alphaBorder, RoundedCornerShape(14.dp)),
                        ) { PatternWallpaper(r.thumb, Modifier.fillMaxSize(), dark = dark) }
                        Text(r.name, color = if (recipe == i) cs.accent else cs.textSecondary, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (recipe >= 0) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PATTERN_RECIPES[recipe].palettes.forEachIndexed { i, p ->
                        val bg = Color(if (dark) p[2] else p[0]); val ink = Color(if (dark) p[3] else p[1])
                        Box(
                            Modifier.padding(end = 10.dp).size(36.dp).clip(CircleShape).background(bg)
                                .border(if (palette == i && !own) 3.dp else 1.dp, if (palette == i && !own) cs.accent else cs.alphaBorder, CircleShape)
                                .clickable { palette = i; own = false },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(14.dp).clip(CircleShape).background(ink)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(cs.bgTertiary)
                            .clickable { seed = (System.nanoTime() and 0x7FFFFFFF) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(stringResource(R.string.wp_pattern_remix), color = cs.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            // Own colours: a chip opens them; the targets, then the palette and hue / saturation / brightness.
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(if (own) cs.accent else cs.bgTertiary)
                        .clickable { if (own) own = false else startOwn() }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Brush.sweepGradient(listOf(
                            Color(0xFFFF4D4D), Color(0xFFFFD84D), Color(0xFF4DFF88), Color(0xFF4DD2FF), Color(0xFF8A4DFF), Color(0xFFFF4DD8), Color(0xFFFF4D4D)))))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.wp_pattern_own), color = if (own) Color.White else cs.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (own) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    @Composable
                    fun Target(t: Int, label: Int) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { target = t }) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(colorOf(t))
                                .border(if (target == t) 3.dp else 1.dp, if (target == t) cs.accent else cs.alphaBorder, CircleShape))
                            Text(stringResource(label), color = if (target == t) cs.accent else cs.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    Target(0, R.string.wp_pattern_bg)
                    if (bgs.size > 1) Target(1, R.string.wp_pattern_bg2)
                    Target(2, R.string.wp_pattern_ink)
                    Spacer(Modifier.weight(1f))
                    // Second background colour: a gradient, and its direction.
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(cs.bgTertiary).clickable {
                            if (bgs.size > 1) { bgs.removeAt(1); if (target == 1) target = 0 } else { bgs.add(bgs.first()); target = 1 }
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(stringResource(if (bgs.size > 1) R.string.wp_pattern_flat else R.string.wp_gradient), color = cs.textPrimary, fontSize = 12.5.sp) }
                    if (bgs.size > 1) Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(cs.bgTertiary)
                            .clickable { degrees = (degrees + 45f) % 360f },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(com.alphamessenger.ui.icons.AlphaIcons.Filled.ArrowDownward, null, tint = cs.accent,
                            modifier = Modifier.size(18.dp).rotate(degrees - 180f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                PALETTE.chunked(8).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c ->
                            Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(8.dp)).background(c)
                                .clickable { hsv.set(c); put(c) })
                        }
                    }
                }
                androidx.compose.runtime.key(target, dark) {
                Label(stringResource(R.string.wp_hue))
                SliderBar(
                    brush = Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 1f))) }),
                    fraction = hsv.h / 360f,
                ) { hsv.h = it * 360f; put(hsv.color) }
                Label(stringResource(R.string.wp_saturation))
                SliderBar(
                    brush = Brush.horizontalGradient(listOf(
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv.h, 0f, hsv.v.coerceAtLeast(0.3f)))),
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv.h, 1f, hsv.v.coerceAtLeast(0.3f)))),
                    )),
                    fraction = hsv.s,
                ) { hsv.s = it; put(hsv.color) }
                Label(stringResource(R.string.wp_brightness))
                SliderBar(
                    brush = Brush.horizontalGradient(listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv.h, hsv.s, 1f))))),
                    fraction = hsv.v,
                ) { hsv.v = it.coerceAtLeast(0.04f); put(hsv.color) }
                }
            }
            val bar = Brush.horizontalGradient(listOf(cs.bgTertiary, cs.accent))
            Label(stringResource(R.string.wp_pattern_blur))
            SliderBar(brush = bar, fraction = blur / 14f) { blur = (it * 14f * 2).toInt() / 2f }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                Text(stringResource(R.string.wp_pattern_blur_old), color = cs.textMuted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
            androidx.compose.runtime.key(recipe) {
            Label(stringResource(R.string.wp_pattern_opacity))
            SliderBar(brush = bar, fraction = (opacity - 0.04f) / 0.56f) { opacity = 0.04f + it * 0.56f }
            Label(stringResource(R.string.wp_pattern_size))
            SliderBar(brush = bar, fraction = (scale - 0.6f) / 1.2f) { scale = 0.6f + it * 1.2f }
            Label(stringResource(R.string.wp_pattern_stroke))
            SliderBar(brush = bar, fraction = (stroke - 0.8f) / 3.2f) { stroke = 0.8f + it * 3.2f }
            }
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(cs.accent, cs.msgOutEnd)))
                    .clickable { onSave(spec) },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.wp_save), color = cs.msgOutText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = AlphaTheme.colorScheme.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}
