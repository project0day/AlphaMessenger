package com.alphamessenger.ui.chat.media.v2.motion

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Current motion status: where it is, where it is going, and how fast.
 *
 * A plain data holder. Velocity is not for reporting: a mid-flight retarget
 * continues from it.
 */
@Stable
class MotionState {

    var current by mutableStateOf(0f)
        internal set

    var target by mutableStateOf(0f)
        internal set

    var velocity by mutableStateOf(0f)
        internal set

    var running by mutableStateOf(false)
        internal set

    var finished by mutableStateOf(false)
        internal set
}
