package pl.skaner.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Zapisany dokument PDF. */
data class ScanDoc(
    val file: File,
    val pages: Int,
    val thumbnail: Bitmap?
) {
    val name: String get() = file.nameWithoutExtension
    val modified: Long get() = file.lastModified()
    val size: Long get() = file.length()
}

/** Pliki PDF trzymane są w prywatnym katalogu aplikacji: files/scans/. */
object ScanRepository {

    private const val THUMB_WIDTH = 180

    fun dir(context: Context): File = File(context.filesDir, "scans").apply { mkdirs() }

    fun defaultName(): String =
        "Skan " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))

    suspend fun list(context: Context): List<ScanDoc> = withContext(Dispatchers.IO) {
        val files = dir(context).listFiles { f -> f.isFile && f.extension.equals("pdf", true) }
            ?: emptyArray()
        files.sortedByDescending { it.lastModified() }.map { file ->
            val (pages, thumb) = readInfo(file)
            ScanDoc(file, pages, thumb)
        }
    }

    /** Liczba stron i miniatura pierwszej strony (PdfRenderer jest wbudowany w Androida). */
    private fun readInfo(file: File): Pair<Int, Bitmap?> = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                val count = renderer.pageCount
                var thumb: Bitmap? = null
                if (count > 0) {
                    val page = renderer.openPage(0)
                    try {
                        val h = THUMB_WIDTH * page.height / page.width
                        thumb = Bitmap.createBitmap(THUMB_WIDTH, h, Bitmap.Config.ARGB_8888)
                        thumb.eraseColor(Color.WHITE)
                        page.render(thumb, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        page.close()
                    }
                }
                count to thumb
            } finally {
                renderer.close()
            }
        }
    } catch (e: Exception) {
        0 to null
    }

    /** Nowy, niezajęty plik o podanej nazwie (znaki niedozwolone zamieniane na „_”). */
    fun newFile(context: Context, name: String): File {
        val base = sanitize(name).ifBlank { defaultName() }
        val dir = dir(context)
        var file = File(dir, "$base.pdf")
        var i = 2
        while (file.exists()) file = File(dir, "$base ($i).pdf").also { i++ }
        return file
    }

    fun rename(context: Context, doc: ScanDoc, newName: String): File? {
        val base = sanitize(newName)
        if (base.isBlank()) return null
        if (base == doc.name) return doc.file
        val target = File(dir(context), "$base.pdf")
        if (target.exists()) return null
        return if (doc.file.renameTo(target)) target else null
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").take(100)
}
