package com.alphamessenger.ui.media.editor

import androidx.annotation.StringRes
import com.alphamessenger.R

/**
 * Aspect ratio of the crop frame.
 *
 * A type, not `Float?`: comparisons like `if (ratio == 0.8f)` spread through
 * the code and break on rounding. A new ratio is one line in [ALL] — no
 * layout or logic changes needed.
 */
sealed interface CropRatio {

    /** Label for the panel. */
    @get:StringRes
    val labelRes: Int

    /** Width / height. Null for the free mode. */
    val value: Float?

    data object Free : CropRatio {
        override val labelRes = R.string.crop_ratio_free
        override val value: Float? = null
    }

    data object Square : CropRatio {
        override val labelRes = R.string.crop_ratio_1_1
        override val value = 1f
    }

    data object Portrait45 : CropRatio {
        override val labelRes = R.string.crop_ratio_4_5
        override val value = 4f / 5f
    }

    data object Portrait34 : CropRatio {
        override val labelRes = R.string.crop_ratio_3_4
        override val value = 3f / 4f
    }

    data object Portrait23 : CropRatio {
        override val labelRes = R.string.crop_ratio_2_3
        override val value = 2f / 3f
    }

    data object Portrait916 : CropRatio {
        override val labelRes = R.string.crop_ratio_9_16
        override val value = 9f / 16f
    }

    data object Landscape169 : CropRatio {
        override val labelRes = R.string.crop_ratio_16_9
        override val value = 16f / 9f
    }

    data object Landscape43 : CropRatio {
        override val labelRes = R.string.crop_ratio_4_3
        override val value = 4f / 3f
    }

    data object Landscape32 : CropRatio {
        override val labelRes = R.string.crop_ratio_3_2
        override val value = 3f / 2f
    }

    /** Placeholder for arbitrary values, should they ever be needed. */
    data class Fixed(override val labelRes: Int, override val value: Float) : CropRatio

    companion object {
        /** Order = order in the panel. */
        val ALL: List<CropRatio> = listOf(
            Free, Square, Portrait45, Portrait34, Portrait23, Portrait916,
            Landscape169, Landscape43, Landscape32,
        )
    }
}
