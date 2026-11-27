package pl.skaner.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/** Warstwa Androida dla [EnhancerCore]: dekodowanie, skalowanie i konwersja Bitmap. */
object ImageEnhancer {

    /** Dłuższy bok strony w PDF (~300 dpi dla A4 to 3508 px; 3000 px daje ~255 dpi). */
    const val FULL_SIZE = 3000

    /** Dłuższy bok podglądu na ekranie. */
    const val PREVIEW_SIZE = 1200

    fun enhance(src: Bitmap, mode: EnhanceMode, sharpen: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        EnhancerCore.process(px, w, h, mode, sharpen)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Wczytuje obraz tak, by dłuższy bok nie przekraczał [maxSide]. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val longSide = max(bounds.outWidth, bounds.outHeight)
        require(longSide > 0) { "Nie można odczytać obrazu: $uri" }

        var sample = 1
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Nie można zdekodować obrazu: $uri")

        val decodedLong = max(decoded.width, decoded.height)
        if (decodedLong <= maxSide) return decoded
        val scale = maxSide.toFloat() / decodedLong
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt(),
            (decoded.height * scale).roundToInt(),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
