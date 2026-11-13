package com.alphamessenger.ui.components

import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted glass: panels show a blurred copy of the content they float over.
 *
 * The content is recorded once per frame into a render node by [glassSource];
 * every [glassPanel] draws that node blurred, shifted by where the panel sits
 * RELATIVE TO THE SOURCE. Relative positions do not change while a screen slides
 * in or out, so the picture under the glass never drifts during transitions.
 * The node is referenced, not copied: scrolling the content updates the glass
 * without redrawing the panels. Below Android 12 the panel is a plain tint.
 */
@Stable
class GlassState {
    internal val content: RenderNode? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderNode("glass-content") else null
    internal var coords: LayoutCoordinates? = null
    /** Bumped when the source moves or resizes: panels re-read their offset. */
    internal val layoutTick = mutableIntStateOf(0)
}

/**
 * How panels look (Appearance): frosted glass, dimmed (see-through, darkened,
 * nothing blurred) or solid. Only the glass records and blurs anything.
 */
object GlassPrefs {
    private const val PREFS = "ui_prefs"
    /** Frosted glass. */
    var enabled by androidx.compose.runtime.mutableStateOf(true)
        private set
    /** Not frosted: the content shows through, darkened by the panel's colour. */
    var dim by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var loaded = false

    fun load(ctx: android.content.Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        enabled = p.getBoolean("glass", true)
        dim = p.getBoolean("glass_dim", false)
    }

    fun set(ctx: android.content.Context, v: Boolean) {
        enabled = v
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean("glass", v).apply()
    }

    /** "frost" | "dim" | "solid". */
    val mode: String get() = if (enabled) "frost" else if (dim) "dim" else "solid"

    fun setMode(ctx: android.content.Context, m: String) {
        enabled = m == "frost"
        dim = m == "dim"
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("glass", enabled).putBoolean("glass_dim", dim).apply()
    }
}

/** The content the glass panels of [state] blur. */
fun Modifier.glassSource(state: GlassState): Modifier = this
    .onGloballyPositioned { state.coords = it; state.layoutTick.intValue++ }
    .drawWithContent {
        val node = state.content
        val out = drawContext.canvas.nativeCanvas
        if (!GlassPrefs.enabled || node == null || !out.isHardwareAccelerated || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            drawContent()
            return@drawWithContent
        }
        node.setPosition(0, 0, size.width.toInt(), size.height.toInt())
        val rec = node.beginRecording()
        val prev = drawContext.canvas
        drawContext.canvas = androidx.compose.ui.graphics.Canvas(rec)
        try { drawContent() } finally { drawContext.canvas = prev; node.endRecording() }
        out.drawRenderNode(node)
    }

/**
 * A frosted panel over [state]'s content: [backdrop] (opaque, hides the sharp
 * content beneath), the blurred content, then [tint] on top — all in [shape].
 */
fun Modifier.glassPanel(
    state: GlassState,
    shape: Shape,
    tint: Color,
    backdrop: Color,
    blur: Dp = 24.dp,
    /** Draw on below the panel's own bounds (e.g. under the navigation bar). */
    extendBottom: Dp = 0.dp,
): Modifier = composed {
    GlassPrefs.load(androidx.compose.ui.platform.LocalContext.current)
    val node = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderNode("glass-panel") else null
    }
    val me = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val tick = remember { mutableIntStateOf(0) }
    // What the blur node was last recorded with. Re-recording it (or giving it a new
    // effect object) tells the GPU "changed — blur again"; when nothing under the
    // glass moved, the GPU reuses the blurred layer it already has.
    val last = remember { FloatArray(5) { Float.NaN } }
    this
        .onGloballyPositioned { me[0] = it; tick.intValue++ }
        .drawBehind {
            // Re-draw (re-read the offset) whenever either side moves.
            tick.intValue; state.layoutTick.intValue
            val full = androidx.compose.ui.geometry.Size(size.width, size.height + extendBottom.toPx())
            val path = Path().apply { addOutline(shape.createOutline(full, layoutDirection, this@drawBehind)) }
            clipPath(path) {
                val src = state.coords
                val self = me[0]
                val out = drawContext.canvas.nativeCanvas
                val content = state.content
                if (GlassPrefs.enabled && node != null && content != null && src != null && self != null &&
                    src.isAttached && self.isAttached && out.isHardwareAccelerated &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    drawRect(backdrop, size = full)
                    val off = src.localPositionOf(self, Offset.Zero)
                    // Blur a copy at 1/4 of the size, then stretch it back: the blur
                    // erases the detail anyway, and the GPU does ~16 times less work.
                    val r = blur.toPx() * DOWNSCALE
                    // The system drops recorded nodes when the app leaves the screen
                    // (trimmed GPU memory): an empty node is recorded again.
                    if (!node.hasDisplayList() || last[0] != full.width || last[1] != full.height || last[2] != off.x || last[3] != off.y || last[4] != r) {
                        last[0] = full.width; last[1] = full.height; last[2] = off.x; last[3] = off.y; last[4] = r
                        node.setPosition(
                            0, 0,
                            kotlin.math.ceil(full.width * DOWNSCALE).toInt().coerceAtLeast(1),
                            kotlin.math.ceil(full.height * DOWNSCALE).toInt().coerceAtLeast(1),
                        )
                        node.setRenderEffect(if (r > 0f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
                        val rec = node.beginRecording()
                        rec.scale(DOWNSCALE, DOWNSCALE)
                        rec.translate(-off.x, -off.y)
                        // A reference, not a copy: when the content changes, this node
                        // shows the new content without being recorded again.
                        rec.drawRenderNode(content)
                        node.endRecording()
                    }
                    out.save()
                    out.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
                    out.drawRenderNode(node)
                    out.restore()
                    drawRect(tint, size = full)
                } else if (GlassPrefs.dim) {
                    // Dimmed: the content shows through, darkened by the panel's colour.
                    drawRect(backdrop.copy(alpha = 0.78f), size = full)
                } else {
                    // No live blur (switched off, or an old Android): a solid panel.
                    drawRect(backdrop, size = full)
                    drawRect(tint, size = full)
                }
            }
        }
}

/** The blurred copy is drawn at this fraction of the panel's size. */
private const val DOWNSCALE = 0.25f
