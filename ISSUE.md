# Issue draft — for penfeizhou/APNG4Android

> This is a **draft** to post at https://github.com/penfeizhou/APNG4Android/issues.
> It is intentionally framed as a library-side bug report, not a request to expose
> internal API. Edit/trim before posting.

---

**Title:** `APNGDrawable` freezes (and restarts from frame 0) on Jetpack Compose recomposition

## Summary

When `APNGDrawable` is rendered in **Jetpack Compose via Coil 3**, the animation
**freezes whenever the hosting composable recomposes** (lists, conditional content,
navigation, crossfades, …). Forcing it back to life restarts the animation **from
frame 0** instead of continuing where it was.

The root cause is in how `FrameAnimationDrawable` / `FrameSeqDecoder` depend on an
external `Drawable.Callback` and on visibility being toggled by the host, plus the
fact that the only documented recovery path (`stop()` → `start()`) is a destructive
teardown that resets the frame index. I think this can be fixed inside the library
without any new public API.

Minimal, self-contained reproduction (sample app, freeze on `main`, app-side
workaround on `solution`):
**https://github.com/tuanchauict/APNG4Android-Compose-Freeze**

## Environment

- APNG4Android: `3.0.5` (also reproduces on later 3.x)
- Coil: `3.x` (`io.coil-kt.coil3`), used via `AsyncImage` with `crossfade(true)`
- Jetpack Compose + Material 3
- Repro device/emulator: API 30+

## Steps to reproduce

1. Decode an APNG with `APNGDrawable` and show it through Coil's `AsyncImage`
   (custom `Decoder` that returns the `APNGDrawable`), with `autoPlay` on.
2. Cause the hosting composable to recompose while the image stays on screen
   (e.g. a sibling state change, a list scroll, navigation, or just the end of
   Coil's crossfade transition).
3. The animation freezes. If something later forces it to run again, it starts
   over from the first frame.

The sample reduces step 2 to two buttons that call exactly what a recomposition
does to the drawable while it remains on screen:
`setVisible(false, false)` (what Coil's `DrawablePainter.onForgotten()` does) and
`frameSeqDecoder.stop()`.

## Root cause (what I found)

`APNGDrawable` produces frames on the decoder thread, but getting them on screen and
keeping the animation alive depends on two things the Compose/Coil host controls and
tears down during recomposition:

1. **External `Drawable.Callback`.** A decoded frame is flushed via
   `invalidateSelf() → getCallback()?.invalidateDrawable(this)`. In Compose that
   callback belongs to Coil's painter (Accompanist `DrawablePainter`), which sets it
   in `onRemembered()` and **nulls it in `onForgotten()`**. With `crossfade(true)`,
   Coil's `CrossfadePainter` also stops self-invalidating once its ~200 ms transition
   ends. After that, `invalidateSelf()` is a no-op and the screen stops updating even
   though the decoder thread is still producing frames.

2. **Visibility-driven start/stop.** With `autoPlay`, `setVisible(false, …)` calls
   `frameSeqDecoder.stop()` (a full teardown — clears frames, recycles bitmaps,
   closes the reader). Coil toggles visibility and also calls `start()`/`stop()` on
   remember/forget, so a recomposition can stop the decoder while the drawable is
   still on screen.

3. **Destructive recovery.** The only way back from `stop()` is `start()`, whose
   `innerStart()` sets `frameIndex = -1` (and `FrameAnimationDrawable.start()` also
   calls `reset()`), so even when playback resumes it **replays from frame 0** rather
   than continuing.

Net effect: the drawable ends up on screen and being drawn, but with its decoder
stopped and/or its callback not delivering invalidations → frozen; and any recovery
loses the playback position.

## Suggested fix (library-side, no new public API)

The library already knows internally whether an animation finished its loops
(`finished` / `getNumPlays()`), so it can recover gracefully on its own:

- **Don't treat a visibility/lifecycle-driven `stop()` as a permanent teardown.**
  Prefer the existing lightweight `pause()` / `resume()` (which preserve
  `frameIndex`, frames and bitmaps) for visibility transitions, so coming back
  resumes mid-animation instead of replaying from frame 0.
- **Don't rely solely on an external `Drawable.Callback` to keep animating.** While
  the drawable is actually being drawn, it can keep scheduling its own redraws (it
  already has the information to know it is on screen via `draw()`), so a host that
  drops the callback doesn't permanently freeze it.
- Only stop permanently when a finite animation has genuinely finished its loop
  count — which the library can already distinguish internally.

## Workaround (for consumers, until a fix lands)

The `solution` branch of the repro implements this from the outside, as a subclass:
force `autoPlay` off, turn external `stop()` / invisibility into
`frameSeqDecoder.pause()`, and on every `draw()` `resume()` if paused (or start the
first play), calling `invalidateSelf()` to keep frames flowing. It resumes from the
current frame and keeps the frame-by-frame, low-memory decoding unchanged.

It is only a workaround: to avoid restarting a finite animation that legitimately
ended, the subclass has to read `FrameSeqDecoder`'s private `finished` flag via
reflection (which breaks under R8/minification). That fragility is exactly why a
proper fix belongs in the library.

## Why this matters

APNG4Android is attractive precisely because it decodes frame-by-frame on a worker
thread, keeping memory low. Compose + Coil 3 is now a very common host, and in that
setup the animation freezing on recomposition makes the library hard to use without
the workaround above.

Happy to help test a fix.
