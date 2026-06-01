# APNG4Android `APNGDrawable` freezes on Jetpack Compose recomposition

A minimal, self-contained reproduction of a long-standing problem: when
[penfeizhou/APNG4Android](https://github.com/penfeizhou/APNG4Android)'s
`APNGDrawable` is rendered in **Jetpack Compose via Coil 3**, the animation
**freezes whenever the hosting composable recomposes** (lists, conditional
content, navigation, crossfades, …).

This repo demonstrates the freeze and a self-contained app-side workaround. The
**proper fix belongs in APNG4Android itself** (see [below](#what-a-library-side-fix-looks-like)) —
the workaround here is what consumers can do *today* while that lands.

| Branch | What it shows |
|---|---|
| [`main`](../../tree/main) | **Reproduces the freeze** with the stock `APNGDrawable`. |
| [`solution`](../../tree/solution) | **An app-side workaround** — a small self-driving `APNGDrawable` subclass that keeps animating across recompositions, without changing APNG4Android's low-memory, frame-by-frame decoding. |

Compare the two branches to see the entire workaround:
[`main...solution`](../../compare/main...solution).

APNG4Android is attractive precisely because it **decodes frame-by-frame on a
worker thread**, so memory stays low — unlike eager decoders that expand every
frame up front. The goal is to keep that property *and* not have the animation
freeze in Compose.

---

## Run it

Requirements: a recent Android Studio / AGP (this sample is pinned to AGP 9.2.1 +
Gradle 9.4.1 + Kotlin 2.2.10, `compileSdk 36`, `minSdk 30`).

```bash
git clone https://github.com/tuanchauict/APNG4Android-Compose-Freeze.git
cd APNG4Android-Compose-Freeze
./gradlew :app:installDebug   # or open in Android Studio and Run
```

The app shows one `AsyncImage` (a bundled APNG of an elephant) plus:

- three **stress buttons** — `Off`, `setVisible(false)`, `decoder.stop()`;
- a live **`frames rendered`** counter (fed by a `FrameSeqDecoder.RenderListener`);
- a **status** line: `▶ animating` vs `❄️ FROZEN`.

The two stress buttons reproduce *exactly* what a recomposition does to the
drawable while it stays on screen — they never remove the image from the tree:

- **`setVisible(false)`** — what Coil's `DrawablePainter.onForgotten()` calls when
  a recomposition forgets the painter;
- **`decoder.stop()`** — the underlying decoder teardown that visibility/lifecycle
  churn triggers.

### What you'll see

- On **`main`** (stock `APNGDrawable`): tap either stress button → the elephant
  freezes and the `frames rendered` counter stops climbing.
- On **`solution`** (`ResilientApngDrawable`): same stress, but the animation keeps
  playing and the counter keeps climbing.

---

## Root cause

`APNGDrawable` produces frames on a background decoder thread, but it relies on two
things Compose + Coil own and tear down during recomposition:

1. **An external `Drawable.Callback`.** A decoded frame is flushed via
   `invalidateSelf() → getCallback()?.invalidateDrawable(this)`. In Compose that
   callback belongs to Coil's painter (Accompanist `DrawablePainter`), which sets it
   in `onRemembered()` and **nulls it in `onForgotten()`**. With `crossfade(true)`,
   Coil's `CrossfadePainter` also stops self-invalidating once its ~200 ms transition
   ends — after that, redraws happen *only* when that callback fires. Once the
   callback is gone, `invalidateSelf()` is a no-op and the screen never updates even
   though the decoder thread is still producing frames.

2. **Visibility-driven start/stop.** With `autoPlay`, `setVisible(false)` →
   `frameSeqDecoder.stop()` (full teardown). Coil toggles visibility on
   remember/forget, so a recomposition can stop the decoder while the drawable is
   still on screen.

Net effect: the drawable ends up **on screen and being drawn, but with its decoder
stopped and/or its callback not delivering invalidations** → frozen.

---

## The app-side workaround (see the [`solution`](../../tree/solution) branch)

Make the drawable **self-driving**, and — crucially — **never let the teardown
happen**, so playback resumes from the *current* frame instead of restarting at
frame 0.

The trap on the recovery path is that penfeizhou's `stop()` is a *full teardown*
(it clears frames, recycles bitmaps, closes the reader), and the only way back from
it is `start()`, whose `innerStart()` resets `frameIndex = -1`. So any stop/start
cycle during recompose replays the animation **from the beginning**. The decoder's
lighter `pause()` / `resume()`, by contrast, keep all state and continue in place.

`ResilientApngDrawable` therefore:

- forces `autoPlay` **off**, so the base class never auto-stops/-restarts the decoder
  from `setVisible(...)`;
- turns any external `stop()` or "became invisible" into a `frameSeqDecoder.pause()`
  (state preserved) instead of a teardown;
- on every `draw()` (i.e. whenever it is actually on screen) **resumes** the decoder
  if it was paused, kicks off the very first play if it never started, and calls
  `invalidateSelf()` so newly decoded frames are flushed even when Coil's callback
  bridge isn't delivering them;
- if a **finite** animation finished on its own, leaves it on the last frame.

Net result: while on screen the animation runs continuously; an off-screen stretch
just pauses it; coming back **resumes mid-animation**. This costs one redraw per
display frame while visible and **does not change the decoding strategy**, so memory
stays low.

### Why this is only a workaround

To avoid restarting a finite animation that legitimately ended, the subclass must
know whether the decoder *finished* vs was *paused/stopped externally*. APNG4Android
exposes no public way to tell these apart (`onAnimationEnd` fires for both), so the
`solution` branch reads `FrameSeqDecoder`'s private `finished` flag **via
reflection** — which works, but breaks under R8/minification when the field is
renamed. That fragility is exactly why this belongs in the library.

## What a library-side fix looks like

The robust fix is for `APNGDrawable` itself to not depend on an external
`Drawable.Callback` and on visibility being toggled correctly by the host — i.e. to
keep driving its own redraws while it is actually being drawn, and not treat a
visibility/lifecycle-driven `stop()` as a permanent end. The library already knows,
internally, whether an animation finished its loops, so it can do this cleanly
without any reflection or new public surface. The `solution` branch is a faithful
sketch of that behaviour, implemented from the outside.

---

## Credits

Reproduction and fix by [@tuanchauict](https://github.com/tuanchauict). The freeze
diagnosis builds on the behaviour of penfeizhou/APNG4Android, Coil 3, and the
Accompanist `DrawablePainter` bridge.
