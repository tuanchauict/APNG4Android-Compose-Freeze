package io.github.tuanchauict.apngfreeze

import android.graphics.Canvas
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.decode.FrameSeqDecoder
import com.github.penfeizhou.animation.loader.Loader

/**
 * An [APNGDrawable] that keeps animating across Jetpack Compose recompositions —
 * and, crucially, **resumes from the current frame** instead of restarting from
 * frame 0 every time the host recomposes.
 *
 * ### Why this exists
 * APNG4Android decodes frame-by-frame on a worker thread (low memory — the whole
 * point of choosing it). To get those frames onto the screen it relies on two things
 * that Compose + Coil control and routinely tear down during recomposition:
 *
 *  1. an external [android.graphics.drawable.Drawable.Callback] — Coil's painter
 *     (Accompanist `DrawablePainter`) sets it in `onRemembered` and **nulls it in
 *     `onForgotten`**, and Coil's `CrossfadePainter` stops driving redraws once its
 *     ~200 ms transition finishes; after that, redraws happen *only* when the
 *     drawable's callback fires `invalidateSelf()`.
 *  2. visibility — with `autoPlay`, [APNGDrawable] starts/stops the decoder from
 *     `setVisible(...)`, and Coil also calls `start()` / `stop()` directly on
 *     remember/forget.
 *
 * The trap is the recovery path: penfeizhou's `stop()` is a *full teardown* (it
 * clears frames, recycles bitmaps, closes the reader), and the only way back from it
 * is `start()`, whose `innerStart()` resets `frameIndex = -1` — so the animation
 * replays **from the beginning** on every recompose.
 *
 * ### The workaround
 * Never let the teardown happen. Instead of `stop()` / `start()` (which lose the
 * frame position), use the decoder's lighter `pause()` / `resume()`, which keep all
 * state and continue from the current frame:
 *
 *  - force `autoPlay` off, so the base class never auto-stops/-restarts the decoder
 *    from `setVisible(...)`;
 *  - treat any external `stop()` / "became invisible" as a [FrameSeqDecoder.pause];
 *  - on every `draw()` (i.e. while actually on screen) [FrameSeqDecoder.resume] if
 *    paused, kick off the very first play if we never started, and call
 *    [invalidateSelf] so newly decoded frames are flushed even when Coil's callback
 *    bridge isn't delivering them.
 *
 * Net result: while on screen the animation runs continuously; an off-screen stretch
 * just pauses it; coming back resumes mid-animation. Decoding stays frame-by-frame
 * and low-memory.
 *
 * It is still only a workaround: the robust fix belongs inside APNG4Android (see the
 * README) — the library should pause/resume across visibility churn rather than tear
 * down and replay from frame 0.
 */
class ResilientApngDrawable(
    loader: Loader,
) : APNGDrawable(loader) {
    init {
        // We drive playback ourselves; see setAutoPlay below.
        super.setAutoPlay(false)
    }

    /**
     * Pin `autoPlay` off no matter what the caller asks for. With `autoPlay` on, the
     * base `setVisible(false, …)` does a full `frameSeqDecoder.stop()` (teardown),
     * which is exactly the reset-to-frame-0 we are avoiding.
     */
    override fun setAutoPlay(autoPlay: Boolean) {
        super.setAutoPlay(false)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val decoder = frameSeqDecoder
        when {
            // Running and paused (e.g. just came back on screen): continue in place.
            decoder.isRunning && decoder.isPaused -> {
                decoder.resume()
                invalidateSelf()
            }
            // Never started, or fully stopped, and not a finite animation that ended:
            // begin the first play. start() initialises the reader and starts at 0.
            !decoder.isRunning && !decoder.hasFinishedNaturally() -> {
                decoder.addRenderListener(this)
                decoder.start()
                invalidateSelf()
            }
            // Already animating: keep flushing frames ourselves, since Coil's callback
            // bridge may not be delivering the decoder's invalidations.
            decoder.isRunning -> invalidateSelf()
            // else: a finite animation finished on its own — leave it on the last frame.
        }
    }

    /**
     * Coil calls `stop()` on `onForgotten`. Turn it into a [FrameSeqDecoder.pause] so
     * the frame position survives the recomposition instead of being torn down.
     */
    override fun stop() {
        frameSeqDecoder.pause()
    }

    /**
     * Coil calls `start()` on `onRemembered`. Resume in place if we were paused;
     * otherwise begin the first play. Notably this avoids the base `start()`, which
     * would `reset()` to frame 0.
     */
    override fun start() {
        val decoder = frameSeqDecoder
        if (decoder.isRunning) {
            decoder.resume()
        } else if (!decoder.hasFinishedNaturally()) {
            decoder.addRenderListener(this)
            decoder.start()
        }
    }

    /**
     * Going invisible (recompose churn or genuinely off-screen) only pauses the
     * decoder; it is resumed from `draw()` / `start()` when shown again. `super` is
     * safe to call because `autoPlay` is forced off, so it won't stop/start.
     */
    override fun setVisible(
        visible: Boolean,
        restart: Boolean,
    ): Boolean {
        if (!visible) {
            frameSeqDecoder.pause()
        }
        return super.setVisible(visible, restart)
    }

    private companion object {
        /**
         * APNG4Android does not expose whether a finite animation finished, so we
         * read its private `finished` flag reflectively. If the field can't be found
         * (e.g. a future library version, or R8 renamed it), we fall back to "not
         * finished", which favours keeping the animation alive — the behaviour we
         * want here.
         *
         * This reflection is the fragile part, and the reason a proper fix belongs in
         * APNG4Android itself: the library already knows internally whether an
         * animation finished its loops.
         */
        private val finishedField =
            runCatching {
                FrameSeqDecoder::class.java
                    .getDeclaredField("finished")
                    .apply { isAccessible = true }
            }.getOrNull()

        fun FrameSeqDecoder<*, *>.hasFinishedNaturally(): Boolean {
            val field = finishedField ?: return false
            return runCatching { field.getBoolean(this) }.getOrDefault(false)
        }
    }
}
