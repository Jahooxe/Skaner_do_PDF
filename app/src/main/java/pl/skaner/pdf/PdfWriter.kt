package pl.skaner.pdf

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Minimalny zapis PDF, w którym każda strona to obraz JPEG osadzony bezpośrednio (/DCTDecode).
 * Pliki są dzięki temu małe (zwykle 150–600 kB na stronę), a strony zapisywane są
 * pojedynczo – w pamięci jest naraz tylko jeden obraz.
 * Strony mają format A4 (pionowo lub poziomo, zależnie od proporcji obrazu).
 */
class PdfWriter(file: File) : Closeable {

    private val out = CountingStream(BufferedOutputStream(FileOutputStream(file)))
    private val offsets = HashMap<Int, Long>()
    private val pageIds = ArrayList<Int>()
    private var nextId = 3 // 1 = Catalog, 2 = Pages (zapisywane na końcu)
    private var closed = false

    init {
        write("%PDF-1.4\n")
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    val pageCount: Int get() = pageIds.size

    fun addJpegPage(jpeg: ByteArray, imgW: Int, imgH: Int) {
        val landscape = imgW > imgH
        val pageW = if (landscape) A4_LONG else A4_SHORT
        val pageH = if (landscape) A4_SHORT else A4_LONG
        val scale = minOf(pageW / imgW, pageH / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val x = (pageW - drawW) / 2
        val y = (pageH - drawH) / 2

        val imageId = nextId++
        val contentId = nextId++
        val pageId = nextId++

        beginObj(imageId)
        write("<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH /ColorSpace /DeviceRGB " +
            "/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
        out.write(jpeg)
        write("\nendstream\nendobj\n")

        val content = String.format(Locale.US, "q %.2f 0 0 %.2f %.2f %.2f cm /Im0 Do Q\n", drawW, drawH, x, y)
        beginObj(contentId)
        write("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")

        beginObj(pageId)
        write(String.format(Locale.US,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] " +
                "/Resources << /XObject << /Im0 %d 0 R >> >> /Contents %d 0 R >>\nendobj\n",
            pageW, pageH, imageId, contentId))
        pageIds.add(pageId)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            beginObj(2)
            write("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>\nendobj\n")
            beginObj(1)
            write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            val xref = out.count
            val size = nextId
            write("xref\n0 $size\n0000000000 65535 f \n")
            for (id in 1 until size) {
                write(String.format(Locale.US, "%010d 00000 n \n", offsets.getValue(id)))
            }
            write("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        } finally {
            out.close()
        }
    }

    private fun beginObj(id: Int) {
        offsets[id] = out.count
        write("$id 0 obj\n")
    }

    private fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

    private class CountingStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L; private set
        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    companion object {
        const val A4_SHORT = 595.28f
        const val A4_LONG = 841.89f
    }
}
