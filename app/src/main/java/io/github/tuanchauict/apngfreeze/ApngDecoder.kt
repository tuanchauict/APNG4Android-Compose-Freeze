package io.github.tuanchauict.apngfreeze

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.decode.FrameSeqDecoder
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

/**
 * A Coil [Decoder] that renders animated PNGs using
 * [APNG4Android](https://github.com/penfeizhou/APNG4Android).
 *
 * Coil's built-in decoders only draw the first frame of an APNG, so we hand the
 * encoded bytes to APNG4Android and expose its [APNGDrawable] to Coil as a
 * [coil3.Image].
 *
 * **On the `main` branch** this builds the stock [APNGDrawable] — which freezes when
 * the host recomposes (see README). The `solution` branch swaps in a self-driving
 * subclass on the single marked line below.
 */
class ApngDecoder(
    private val source: ImageSource,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        // Read the whole encoded image so the drawable can re-read it on every loop.
        val bytes = source.source().use { it.readByteArray() }
        val loader =
            object : ByteBufferLoader() {
                override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
            }

        // === The one line that differs between the `main` and `solution` branches. ===
        val drawable: APNGDrawable = ResilientApngDrawable(loader)
        // =============================================================================

        // Begin animating as soon as the drawable becomes visible.
        drawable.setAutoPlay(true)

        // Demo instrumentation: publish the drawable + reset/feed the frame counter so
        // the UI can stress it and show whether decoding is still happening.
        ApngDemoState.live = WeakReference(drawable)
        ApngDemoState.renderCount.set(0)
        drawable.frameSeqDecoder.addRenderListener(
            object : FrameSeqDecoder.RenderListener {
                override fun onStart() = Unit

                override fun onRender(byteBuffer: ByteBuffer?) {
                    ApngDemoState.renderCount.incrementAndGet()
                }

                override fun onEnd() = Unit
            },
        )

        return DecodeResult(
            image = drawable.asImage(),
            isSampled = false,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isApng(result.source.source())) return null
            return ApngDecoder(result.source)
        }

        /**
         * Returns true only for *animated* PNGs. A file is an APNG when it starts
         * with the PNG signature and contains an `acTL` (animation control) chunk,
         * which is what separates an APNG from an ordinary static PNG.
         */
        private fun isApng(source: BufferedSource): Boolean {
            // `rangeEquals` buffers the bytes without consuming them, so the decoder
            // can still read the full stream afterwards.
            if (!source.rangeEquals(0L, PNG_SIGNATURE)) return false
            // Peek so the search does not consume the source either.
            return source.peek().indexOf(ACTL_CHUNK) != -1L
        }

        private companion object {
            val PNG_SIGNATURE =
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4E,
                    0x47,
                    0x0D,
                    0x0A,
                    0x1A,
                    0x0A,
                ).toByteString()
            val ACTL_CHUNK = "acTL".encodeUtf8()
        }
    }
}
