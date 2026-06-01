package io.github.tuanchauict.apngfreeze

import com.github.penfeizhou.animation.apng.APNGDrawable
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared hand-off between [ApngDecoder] (which creates the drawable on a Coil
 * worker thread) and the demo UI (which pokes it to reproduce a recomposition and
 * watches whether it keeps decoding).
 *
 * This exists only to make the freeze *provable* on screen — it is not part of the
 * fix and not something a real app needs.
 */
object ApngDemoState {
    /** Incremented on every decoded frame, by whichever drawable is live. */
    val renderCount = AtomicInteger(0)

    /** The most recently created, on-screen drawable, so the UI can stress it. */
    @Volatile
    var live: WeakReference<APNGDrawable>? = null
}
