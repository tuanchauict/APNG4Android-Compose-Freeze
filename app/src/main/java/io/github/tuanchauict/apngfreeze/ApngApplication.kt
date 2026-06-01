package io.github.tuanchauict.apngfreeze

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

/**
 * Provides the app-wide Coil [ImageLoader]. Implementing
 * [SingletonImageLoader.Factory] lets composables such as `AsyncImage` pick this
 * configuration up automatically, with no per-call wiring.
 *
 * Note `crossfade(true)`: this is part of what triggers the freeze. Coil's
 * `CrossfadePainter` only self-invalidates while its ~200 ms transition runs;
 * after that, redraws depend entirely on the drawable's `Drawable.Callback`.
 */
class ApngApplication :
    Application(),
    SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                // Decode animated PNGs with APNG4Android.
                add(ApngDecoder.Factory())
                // Fetch images over the network with OkHttp (not used by the bundled
                // asset, but kept so you can also point the demo at a remote APNG URL).
                add(OkHttpNetworkFetcherFactory())
            }.crossfade(true)
            .build()
}
