package com.alphamessenger.ui.chat.v2

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alphamessenger.R
import com.alphamessenger.ui.chat.text.MarkdownKind
import com.alphamessenger.ui.theme.AlphaColorScheme

/** The composer's platform text field: selection, its menu and scrolling are the system's own. */
class ComposerEditText(context: Context, attrs: AttributeSet?) : EditText(context, attrs) {
    internal var onSelection: (() -> Unit)? = null
    /** Set while the text is written from outside, so it is not echoed back. */
    internal var syncing = false
    internal var appliedColors: AlphaColorScheme? = null

    private val box = IntArray(6) { -1 }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (selStart != selEnd && !syncing) holdKeyboardUpdates() else releaseKeyboardUpdates()
        if (!syncing) onSelection?.invoke()
    }

    /*
     * The keyboard sets its capitals from the text before the selection start, so a
     * handle dragged upward flips them at every sentence it passes. A batch edit is the
     * platform's way to hold selection reports back from the keyboard: it stays open
     * while a range keeps moving and closes once it rests, sending one final report.
     */
    private var keyboardHeld = false
    private val releaseHold = Runnable { releaseKeyboardUpdates() }

    private fun holdKeyboardUpdates() {
        if (!keyboardHeld) { keyboardHeld = true; beginBatchEdit() }
        removeCallbacks(releaseHold)
        postDelayed(releaseHold, 300)
    }

    internal fun releaseKeyboardUpdates() {
        removeCallbacks(releaseHold)
        if (keyboardHeld) { keyboardHeld = false; endBatchEdit() }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        if (!focused) releaseKeyboardUpdates()
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
    }

    override fun onDetachedFromWindow() {
        releaseKeyboardUpdates()
        super.onDetachedFromWindow()
    }

    /**
     * Size limits and padding, applied only when they change. Each setter requests a
     * layout, and while one is pending the field defers the scroll that keeps a
     * dragged selection handle in view, so the drag would stall every frame.
     */
    internal fun applyBox(minH: Int, maxH: Int, left: Int, top: Int, right: Int, bottom: Int) {
        if (box[0] != minH) { box[0] = minH; minHeight = minH }
        if (box[1] != maxH) { box[1] = maxH; maxHeight = maxH }
        if (box[2] != left || box[3] != top || box[4] != right || box[5] != bottom) {
            box[2] = left; box[3] = top; box[4] = right; box[5] = bottom
            setPadding(left, top, right, bottom)
        }
    }

    // Typing replaces a selected range, so capitals are asked of the cursor only once
    // it is a single point again.
    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : android.view.inputmethod.InputConnectionWrapper(ic, false) {
            override fun getCursorCapsMode(reqModes: Int): Int =
                if (selectionStart != selectionEnd) 0 else super.getCursorCapsMode(reqModes)
        }
    }

    // Connecting to the keyboard asks for a neighbouring editor (for "Next") by a focus
    // search that runs into Compose, which walks the whole screen: seconds, up to an
    // ANR. The field stands alone, so there is no neighbour to find.
    override fun focusSearch(direction: Int): android.view.View? =
        if (direction == FOCUS_UP || direction == FOCUS_DOWN) null else super.focusSearch(direction)

    // Formatting travels as markers in the text, so pasted styling is dropped.
    override fun onTextContextMenuItem(id: Int): Boolean =
        super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id)

    internal fun value() = TextFieldValue(text?.toString().orEmpty(), TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0)))
}

private const val ID_FORMAT = 0x7A1000


/**
 * The app's text field on the platform's own widget: selection, its menu (with our
 * formatting entries), scrolling under a dragged handle and the scroll bar are the
 * system's. [value] is the truth; the field reports an edit through [onEdit] and a
 * selection move over the same text through [onSelect], and takes any change made
 * elsewhere (mention, format, emoji, draft, clear after send).
 */
@Composable
fun PlatformTextField(
    value: TextFieldValue,
    onEdit: (TextFieldValue) -> Unit,
    onSelect: (TextRange) -> Unit,
    hint: String,
    colors: AlphaColorScheme,
    textSizeSp: Float,
    lineHeightSp: Float,
    minHeight: Dp,
    maxHeight: Dp,
    padding: FieldPadding,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onTapped: () -> Unit = {},
    onMultiline: () -> Unit = {},
    /** A thin scroll bar at the right while the text is taller than the field. */
    scrollBar: Boolean = true,
    onField: (EditText?) -> Unit = {},
) {
    val density = LocalDensity.current
    val labels = composerFormatLabels()
    val current = rememberUpdatedState(value)
    val edit = rememberUpdatedState(onEdit)
    val select = rememberUpdatedState(onSelect)
    val tapped = rememberUpdatedState(onTapped)
    val multiline = rememberUpdatedState(onMultiline)
    val field = rememberUpdatedState(onField)
    val currentLabels = rememberUpdatedState(labels)
    fun px(dp: Dp) = with(density) { dp.roundToPx() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val et = (LayoutInflater.from(ctx).inflate(R.layout.composer_field, null) as ComposerEditText).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                if (Build.VERSION.SDK_INT >= 28) lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineHeightSp, resources.displayMetrics).toInt()
                scrollBarSize = px(3.dp)
                isVerticalScrollBarEnabled = scrollBar
                // Only an edit carries text out. A selection move carries only the selection,
                // and only over the same text: one arriving after the text was replaced from
                // outside (sent, cleared) would otherwise bring the old text back.
                onSelection = {
                    val v = value()
                    val held = current.value
                    if (v.text == held.text && v.selection != held.selection) select.value(v.selection)
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (!syncing && s?.toString() != current.value.text) edit.value(value())
                    }
                })
                // A tap, not a focus change: focus also returns on its own after a clear.
                setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) tapped.value(); false }
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if ((v as EditText).lineCount > 1) multiline.value() }
                customSelectionActionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                        currentLabels.value.forEachIndexed { i, label ->
                            menu.add(Menu.NONE, ID_FORMAT + i, 100 + i, label).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        return true
                    }
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                        val kind = COMPOSER_FORMATS.getOrNull(item.itemId - ID_FORMAT) ?: return false
                        edit.value(applyMarkup(value(), kind))
                        return true
                    }
                    override fun onDestroyActionMode(mode: ActionMode) {}
                }
                field.value(this)
            }
            // Compose sets the minimum size of the view it hosts on every measure, which
            // requests a layout; the field would then defer the scroll that follows a
            // dragged selection handle. The holder takes those calls instead.
            android.widget.FrameLayout(ctx).apply {
                addView(et, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        },
        onRelease = { field.value(null) },
        update = { holder ->
            val et = holder.getChildAt(0) as ComposerEditText
            et.isEnabled = enabled
            if (et.hint?.toString() != hint) et.hint = hint
            et.applyBox(
                px(minHeight), px(maxHeight),
                px(padding.start), px(padding.top), px(padding.end), px(padding.bottom),
            )
            if (et.appliedColors != colors) {
                et.appliedColors = colors
                et.setTextColor(colors.textPrimary.toArgb())
                et.setHintTextColor(colors.textMuted.toArgb())
                et.highlightColor = colors.accent.copy(alpha = 0.35f).toArgb()
                if (Build.VERSION.SDK_INT >= 29) {
                    et.textCursorDrawable = GradientDrawable().apply { setColor(colors.accent.toArgb()); setSize(px(2.dp), 0) }
                    listOf(et.textSelectHandle, et.textSelectHandleLeft, et.textSelectHandleRight).forEachIndexed { i, d ->
                        val tinted = d?.mutate()?.apply { setTint(colors.accent.toArgb()) } ?: return@forEachIndexed
                        when (i) { 0 -> et.setTextSelectHandle(tinted); 1 -> et.setTextSelectHandleLeft(tinted); else -> et.setTextSelectHandleRight(tinted) }
                    }
                    val r = px(2.dp).toFloat()
                    et.verticalScrollbarThumbDrawable = GradientDrawable().apply { setColor(colors.textMuted.copy(alpha = 0.75f).toArgb()); cornerRadius = r }
                    et.verticalScrollbarTrackDrawable = GradientDrawable().apply { setColor(colors.textMuted.copy(alpha = 0.18f).toArgb()); cornerRadius = r }
                }
            }
            val len = value.text.length
            if (et.text?.toString() != value.text) {
                et.syncing = true
                et.setText(value.text)
                et.setSelection(value.selection.start.coerceIn(0, len), value.selection.end.coerceIn(0, len))
                et.syncing = false
            } else if (et.selectionStart != value.selection.start || et.selectionEnd != value.selection.end) {
                et.syncing = true
                et.setSelection(value.selection.start.coerceIn(0, len), value.selection.end.coerceIn(0, len))
                et.syncing = false
            }
        },
    )
}

/** Inner space of a [PlatformTextField], around its text. */
data class FieldPadding(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

/** The chat composer's field: [BottomSurfaceState.textFieldValue] is its truth. */
@Composable
internal fun ComposerField(
    state: BottomSurfaceState,
    enabled: Boolean,
    hint: String,
    colors: AlphaColorScheme,
    twoRow: Boolean,
    maxHeight: Dp,
    onChange: (TextFieldValue) -> Unit,
    onTapped: () -> Unit,
    onMultiline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformTextField(
        value = state.textFieldValue,
        onEdit = onChange,
        // Straight into the state: a selection never touches the text the chat holds.
        onSelect = { state.textFieldValue = state.textFieldValue.copy(selection = it) },
        hint = hint,
        colors = colors,
        textSizeSp = 15f,
        lineHeightSp = 20f,
        minHeight = 44.dp,
        maxHeight = maxHeight,
        padding = FieldPadding(
            start = if (twoRow) 16.dp else 2.dp, top = 12.dp,
            end = if (twoRow) 18.dp else 4.dp, bottom = if (twoRow) 4.dp else 12.dp,
        ),
        modifier = modifier,
        enabled = enabled,
        onTapped = onTapped,
        onMultiline = onMultiline,
        onField = { state.field = it },
    )
}

/** Cursor into the field and the keyboard up. */
internal fun EditText.focusAndShowKeyboard() {
    requestFocus()
    post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0) }
}

/** Keyboard down and the cursor out of the field. */
internal fun EditText.hideKeyboardAndBlur() {
    context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    if (hasFocus()) clearFocus()
}

/** Keyboard down; the cursor stays where it is. */
internal fun EditText.hideKeyboard() {
    context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
}
