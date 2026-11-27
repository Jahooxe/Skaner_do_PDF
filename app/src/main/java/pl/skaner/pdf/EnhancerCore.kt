package pl.skaner.pdf

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Tryby poprawy wyglądu skanu. */
enum class EnhanceMode { ORIGINAL, COLOR, GRAY, BLACK_WHITE }

/**
 * Algorytmy poprawy skanu działające na tablicy pikseli ARGB (bez zależności od Androida).
 *
 * 1. Szacowanie tła (koloru papieru) w siatce bloków – 95. percentyl jasności bloku.
 *    Bloki z treścią (kolorowe lub dużo ciemniejsze od papieru) są uzupełniane z sąsiadów,
 *    potem siatka jest rozmywana. Dzięki temu znika cień, zażółcenie i plamy.
 * 2. Normalizacja: piksel / tło -> papier staje się biały.
 * 3. Krzywa poziomów (punkt czerni, bieli i gamma) – czyści resztki brudu, przyciemnia tekst.
 * 4. Wyostrzanie maską nieostrą (unsharp mask).
 * 5. Tryb czarno-biały: progowanie adaptacyjne Bradleya + usuwanie pojedynczych kropek.
 */
object EnhancerCore {

    private const val BLACK_POINT = 30
    private const val WHITE_POINT = 225
    private const val GAMMA = 1.3
    private const val SHARPEN_AMOUNT_X10 = 8 // 0.8

    private val LEVELS = IntArray(256) { i ->
        val x = ((i - BLACK_POINT).toDouble() / (WHITE_POINT - BLACK_POINT)).coerceIn(0.0, 1.0)
        (255 * x.pow(GAMMA)).roundToInt()
    }

    fun process(px: IntArray, w: Int, h: Int, mode: EnhanceMode, sharpen: Boolean) {
        require(px.size == w * h)
        if (mode != EnhanceMode.ORIGINAL) normalizeBackground(px, w, h)
        when (mode) {
            EnhanceMode.ORIGINAL -> if (sharpen) sharpenRgb(px, w, h)
            EnhanceMode.COLOR -> {
                for (i in px.indices) {
                    val c = px[i]
                    px[i] = rgb(LEVELS[(c shr 16) and 0xFF], LEVELS[(c shr 8) and 0xFF], LEVELS[c and 0xFF])
                }
                if (sharpen) sharpenRgb(px, w, h)
            }
            EnhanceMode.GRAY -> {
                val lum = IntArray(w * h) { LEVELS[lum(px[it])] }
                if (sharpen) sharpenChannel(lum, w, h, IntArray(w * h), IntArray(w * h))
                for (i in px.indices) { val v = lum[i]; px[i] = rgb(v, v, v) }
            }
            EnhanceMode.BLACK_WHITE -> binarize(px, w, h, sharpen)
        }
    }

    // ---------------------------------------------------------------- tło

    private fun normalizeBackground(px: IntArray, w: Int, h: Int) {
        val block = max(8, max(w, h) / 120)
        val gw = (w + block - 1) / block
        val gh = (h + block - 1) / block
        val n = gw * gh
        val gr = IntArray(n); val gg = IntArray(n); val gb = IntArray(n)
        val hist = IntArray(256)

        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x0 = gx * block; val x1 = min(w, x0 + block)
            val y0 = gy * block; val y1 = min(h, y0 + block)
            hist.fill(0)
            for (y in y0 until y1) { val row = y * w; for (x in x0 until x1) hist[lum(px[row + x])]++ }
            val count = (x1 - x0) * (y1 - y0)
            val need = max(1, count * 5 / 100)
            var acc = 0; var t = 255
            while (t > 0) { acc += hist[t]; if (acc >= need) break; t-- }
            var sr = 0L; var sg = 0L; var sb = 0L; var k = 0
            for (y in y0 until y1) {
                val row = y * w
                for (x in x0 until x1) {
                    val c = px[row + x]
                    if (lum(c) >= t) { sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; k++ }
                }
            }
            val idx = gy * gw + gx
            gr[idx] = (sr / k).toInt(); gg[idx] = (sg / k).toInt(); gb[idx] = (sb / k).toInt()
        }

        fillContentBlocks(gr, gg, gb, gw, gh)
        for (ch in arrayOf(gr, gg, gb)) {
            dilate(ch, gw, gh)
            val tmp = IntArray(n); val out = IntArray(n)
            boxBlur(ch, out, tmp, gw, gh, 2); boxBlur(out, ch, tmp, gw, gh, 2)
            for (i in 0 until n) if (ch[i] < 90) ch[i] = 90
        }

        // współczynniki interpolacji dwuliniowej (stałoprzecinkowo, 0..256)
        val xi0 = IntArray(w); val xi1 = IntArray(w); val xw = IntArray(w)
        for (x in 0 until w) {
            val f = ((x + 0.5) / block - 0.5).coerceIn(0.0, (gw - 1).toDouble())
            xi0[x] = f.toInt(); xi1[x] = min(xi0[x] + 1, gw - 1); xw[x] = ((f - xi0[x]) * 256).toInt()
        }
        for (y in 0 until h) {
            val f = ((y + 0.5) / block - 0.5).coerceIn(0.0, (gh - 1).toDouble())
            val r0 = f.toInt() * gw; val r1 = min(f.toInt() + 1, gh - 1) * gw
            val wy = ((f - f.toInt()) * 256).toInt()
            val row = y * w
            for (x in 0 until w) {
                val a = r0 + xi0[x]; val b = r0 + xi1[x]; val c = r1 + xi0[x]; val d = r1 + xi1[x]
                val wx = xw[x]
                val bgR = bilerp(gr[a], gr[b], gr[c], gr[d], wx, wy)
                val bgG = bilerp(gg[a], gg[b], gg[c], gg[d], wx, wy)
                val bgB = bilerp(gb[a], gb[b], gb[c], gb[d], wx, wy)
                val p = px[row + x]
                px[row + x] = rgb(
                    min(255, ((p shr 16) and 0xFF) * 255 / bgR),
                    min(255, ((p shr 8) and 0xFF) * 255 / bgG),
                    min(255, (p and 0xFF) * 255 / bgB)
                )
            }
        }
    }

    /** Bloki, które wyglądają na treść, a nie na papier, zastępuje średnią sąsiednich bloków tła. */
    private fun fillContentBlocks(gr: IntArray, gg: IntArray, gb: IntArray, gw: Int, gh: Int) {
        val n = gw * gh
        val gl = IntArray(n) { (77 * gr[it] + 150 * gg[it] + 29 * gb[it]) shr 8 }
        val paper = gl.sortedArray()[min(n - 1, n * 9 / 10)]
        val valid = BooleanArray(n) {
            val sat = maxOf(gr[it], gg[it], gb[it]) - minOf(gr[it], gg[it], gb[it])
            gl[it] * 100 >= paper * 45 && sat < 45
        }
        if (valid.none { it }) return
        repeat(max(gw, gh)) {
            if (valid.all { it }) return
            val fills = ArrayList<IntArray>()
            for (y in 0 until gh) for (x in 0 until gw) {
                val i = y * gw + x
                if (valid[i]) continue
                var r = 0; var g = 0; var b = 0; var c = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = y + dy; val xx = x + dx
                    if (yy < 0 || yy >= gh || xx < 0 || xx >= gw) continue
                    val j = yy * gw + xx
                    if (valid[j]) { r += gr[j]; g += gg[j]; b += gb[j]; c++ }
                }
                if (c > 0) fills.add(intArrayOf(i, r / c, g / c, b / c))
            }
            for (f in fills) { gr[f[0]] = f[1]; gg[f[0]] = f[2]; gb[f[0]] = f[3]; valid[f[0]] = true }
        }
    }

    private fun dilate(ch: IntArray, w: Int, h: Int) {
        val src = ch.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1); val xx = (x + dx).coerceIn(0, w - 1)
                m = max(m, src[yy * w + xx])
            }
            ch[y * w + x] = m
        }
    }

    // ---------------------------------------------------------------- wyostrzanie / B&W

    private fun sharpenRgb(px: IntArray, w: Int, h: Int) {
        val ch = IntArray(w * h); val tmp = IntArray(w * h); val blur = IntArray(w * h)
        for (shift in intArrayOf(16, 8, 0)) {
            for (i in px.indices) ch[i] = (px[i] shr shift) and 0xFF
            sharpenChannel(ch, w, h, tmp, blur)
            val mask = (0xFF shl shift).inv()
            for (i in px.indices) px[i] = (px[i] and mask) or (ch[i] shl shift)
        }
    }

    private fun sharpenChannel(ch: IntArray, w: Int, h: Int, tmp: IntArray, blur: IntArray) {
        boxBlur(ch, blur, tmp, w, h, 1)
        for (i in ch.indices) {
            val v = ch[i]
            ch[i] = (v + (v - blur[i]) * SHARPEN_AMOUNT_X10 / 10).coerceIn(0, 255)
        }
    }

    private fun binarize(px: IntArray, w: Int, h: Int, sharpen: Boolean) {
        val lum = IntArray(w * h) { lum(px[it]) }
        val tmp = IntArray(w * h); val mean = IntArray(w * h)
        if (sharpen) sharpenChannel(lum, w, h, tmp, mean)
        boxBlur(lum, mean, tmp, w, h, max(8, max(w, h) / 60))
        val black = BooleanArray(w * h) {
            val v = lum[it]
            (v * 100 < mean[it] * 85 && v < 215) || v < 110
        }
        // usuwanie pojedynczych kropek (czarny piksel z < 2 czarnymi sąsiadami)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var isBlack = black[i]
            if (isBlack) {
                var nb = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val yy = y + dy; val xx = x + dx
                    if (yy in 0 until h && xx in 0 until w && black[yy * w + xx]) nb++
                }
                isBlack = nb >= 2
            }
            px[i] = if (isBlack) rgb(0, 0, 0) else rgb(255, 255, 255)
        }
    }

    // ---------------------------------------------------------------- narzędzia

    /** Separowalny filtr pudełkowy o promieniu r (brzegi powielane). src -> dst, tmp pomocnicza. */
    fun boxBlur(src: IntArray, dst: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / div
                sum += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / div
                sum += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x]
            }
        }
    }

    private fun bilerp(a: Int, b: Int, c: Int, d: Int, wx: Int, wy: Int): Int {
        val top = a * (256 - wx) + b * wx
        val bot = c * (256 - wx) + d * wx
        return max(1, (top * (256 - wy) + bot * wy) shr 16)
    }

    private fun lum(c: Int): Int =
        (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
