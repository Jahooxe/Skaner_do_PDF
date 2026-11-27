package pl.skaner.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Operacje na stronach: zapis zdjęć, wykrywanie kartki, obrót i renderowanie wyniku. */
object PageProcessor {

    /** Dłuższy bok zachowywanego oryginału zdjęcia. */
    private const val ORIGINAL_MAX = 4000

    /** Dłuższy bok strony w PDF (A4 przy ~255 dpi). */
    const val FULL_SIZE = 3000

    /** Dłuższy bok podglądu na ekranie. */
    const val PREVIEW_SIZE = 1200

    private const val DETECT_SIZE = 800

    /** Zdjęcie z aparatu → oryginał strony (obrócony pionowo) + wykryty kadr. */
    fun fromCamera(context: Context, photo: Bitmap, rotationDegrees: Int): ScanPage {
        var bmp = downscale(photo, ORIGINAL_MAX)
        if (rotationDegrees != 0) bmp = rotate(bmp, rotationDegrees)
        return store(context, bmp)
    }

    /** Obraz z galerii (z uwzględnieniem orientacji EXIF) → oryginał strony + wykryty kadr. */
    fun fromUri(context: Context, uri: Uri): ScanPage {
        var bmp = decodeUri(context, uri, ORIGINAL_MAX)
        val rotation = try {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (e: Exception) { 0 }
        if (rotation != 0) bmp = rotate(bmp, rotation)
        return store(context, bmp)
    }

    private fun store(context: Context, bmp: Bitmap): ScanPage {
        val file = ScanSession.newImageFile(context)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val frame = detect(bmp)
        bmp.recycle()
        return ScanPage(UUID.randomUUID().toString(), file.absolutePath, frame)
    }

    /** Automatyczne wykrycie kartki; zwraca kadr znormalizowany (0..1) albo całe zdjęcie. */
    fun detect(bmp: Bitmap): PageFrame {
        val s = DETECT_SIZE.toFloat() / max(bmp.width, bmp.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true) else bmp
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        return PageDetector.detect(px, w, h)?.scaled(1f / w, 1f / h) ?: PageFrame.full(1f, 1f)
    }

    fun detect(page: ScanPage): PageFrame {
        val bmp = decodeFile(page.file, DETECT_SIZE)
        return detect(bmp).also { bmp.recycle() }
    }

    /** Obraca oryginał strony o 90° w prawo (razem z kadrem). */
    fun rotate(page: ScanPage): ScanPage {
        val bmp = decodeFile(page.file, ORIGINAL_MAX)
        val rotated = rotate(bmp, 90)
        FileOutputStream(page.file).use { rotated.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        rotated.recycle()
        return page.copy(frame = page.frame.rotatedCw(1f))
    }

    /** Wynik: wycięta, wyprostowana i poprawiona strona o dłuższym boku ≤ [maxSide]. */
    fun render(page: ScanPage, maxSide: Int, mode: EnhanceMode, sharpen: Boolean): Bitmap {
        val src = decodeFile(page.file, (maxSide * 1.35f).roundToInt())
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        val frame = page.frame.scaled(w.toFloat(), h.toFloat())
        val size = PageWarp.outputSize(frame, maxSide)
        val out = PageWarp.warp(px, w, h, frame, size[0], size[1])
        PageWarp.cleanBorders(out, size[0], size[1])
        EnhancerCore.process(out, size[0], size[1], mode, sharpen)
        return Bitmap.createBitmap(out, size[0], size[1], Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- dekodowanie

    fun decodeFile(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: error("Nie można odczytać obrazu strony.")
        return downscale(bmp, maxSide)
    }

    private fun decodeUri(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0) { "Nie można odczytać obrazu." }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Nie można zdekodować obrazu.")
        return downscale(bmp, maxSide)
    }

    private fun sampleSize(longSide: Int, maxSide: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun downscale(bmp: Bitmap, maxSide: Int): Bitmap {
        val long = max(bmp.width, bmp.height)
        if (long <= maxSide) return bmp
        val s = maxSide.toFloat() / long
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (r !== bmp) bmp.recycle()
        return r
    }
}
