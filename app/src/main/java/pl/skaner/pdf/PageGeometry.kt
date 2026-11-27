package pl.skaner.pdf

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Kadr strony: 8 punktów (x, y) – rogi i środki krawędzi, w kolejności:
 * 0 lewy-górny, 1 środek góry, 2 prawy-górny, 3 środek prawej, 4 prawy-dolny,
 * 5 środek dołu, 6 lewy-dolny, 7 środek lewej.
 * Środki krawędzi pozwalają opisać wygiętą kartkę (krawędź = krzywa przez róg–środek–róg).
 */
class PageFrame(val pts: FloatArray) {
    init { require(pts.size == 16) }

    fun x(i: Int) = pts[2 * i]
    fun y(i: Int) = pts[2 * i + 1]
    fun set(i: Int, x: Float, y: Float) { pts[2 * i] = x; pts[2 * i + 1] = y }
    fun copy() = PageFrame(pts.copyOf())
    fun scaled(sx: Float, sy: Float) = PageFrame(FloatArray(16) { if (it % 2 == 0) pts[it] * sx else pts[it] * sy })

    /** Obrót kadru razem z obrazem o 90° w prawo (obraz w×h → h×w). */
    fun rotatedCw(h: Float): PageFrame {
        val out = FloatArray(16)
        for (i in 0 until 8) {
            // punkt (x, y) → (h - y, x); kolejność punktów przesuwa się o 2 (lewy-dolny staje się lewym-górnym)
            val j = (i + 2) % 8
            out[2 * j] = h - y(i)
            out[2 * j + 1] = x(i)
        }
        return PageFrame(out)
    }

    companion object {
        const val TL = 0; const val TR = 2; const val BR = 4; const val BL = 6

        fun fromCorners(tl: FloatArray, tr: FloatArray, br: FloatArray, bl: FloatArray): PageFrame {
            val c = arrayOf(tl, tr, br, bl)
            val p = FloatArray(16)
            for (k in 0 until 4) {
                val a = c[k]; val b = c[(k + 1) % 4]
                p[4 * k] = a[0]; p[4 * k + 1] = a[1]
                p[4 * k + 2] = (a[0] + b[0]) / 2; p[4 * k + 3] = (a[1] + b[1]) / 2
            }
            return PageFrame(p)
        }

        fun full(w: Float, h: Float) =
            fromCorners(floatArrayOf(0f, 0f), floatArrayOf(w, 0f), floatArrayOf(w, h), floatArrayOf(0f, h))

        fun fromString(s: String) = PageFrame(s.split(',').map { it.toFloat() }.toFloatArray())
    }

    override fun toString() = pts.joinToString(",")
}

/**
 * Prostowanie strony: homografia (perspektywa) z 4 rogów + płat Coonsa (wygięte krawędzie).
 * Gdy krawędzie są proste, wynik to czysta korekta perspektywy.
 */
object PageWarp {

    private const val A4 = 1.41421356

    /** Rozmiar wyniku na podstawie długości krawędzi; proporcje bliskie A4 są dociągane do A4. */
    fun outputSize(f: PageFrame, maxSide: Int): IntArray {
        val m = Mapper(f)
        fun len(edge: Int): Double {
            var l = 0.0
            var px = 0.0; var py = 0.0
            for (k in 0..24) {
                val t = k / 24.0
                val (u, v) = when (edge) { 0 -> t to 0.0; 1 -> 1.0 to t; 2 -> t to 1.0; else -> 0.0 to t }
                val p = m.map(u, v)
                if (k > 0) l += hypot(p[0] - px, p[1] - py)
                px = p[0]; py = p[1]
            }
            return l
        }
        var w = (len(0) + len(2)) / 2
        var h = (len(1) + len(3)) / 2
        if (w < 1 || h < 1) return intArrayOf(1, 1)
        val ratio = max(w, h) / min(w, h)
        if (abs(ratio - A4) / A4 < 0.07) { if (h >= w) h = w * A4 else w = h * A4 }
        val scale = min(1.0, maxSide / max(w, h))
        return intArrayOf(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
    }

    /** Wycina i prostuje stronę z obrazu źródłowego (piksele ARGB). Kadr w pikselach źródła. */
    fun warp(src: IntArray, sw: Int, sh: Int, f: PageFrame, outW: Int, outH: Int): IntArray {
        val m = Mapper(f)
        val out = IntArray(outW * outH)
        // brzegi płata Coonsa w przestrzeni wyprostowanej (kwadrat jednostkowy)
        val tx = DoubleArray(outW); val ty = DoubleArray(outW); val bx = DoubleArray(outW); val by = DoubleArray(outW)
        for (x in 0 until outW) {
            val u = (x + 0.5) / outW
            m.edge(0, u).let { tx[x] = it[0]; ty[x] = it[1] }
            m.edge(2, u).let { bx[x] = it[0]; by[x] = it[1] }
        }
        val lx = DoubleArray(outH); val ly = DoubleArray(outH); val rx = DoubleArray(outH); val ry = DoubleArray(outH)
        for (y in 0 until outH) {
            val v = (y + 0.5) / outH
            m.edge(3, v).let { lx[y] = it[0]; ly[y] = it[1] }
            m.edge(1, v).let { rx[y] = it[0]; ry[y] = it[1] }
        }
        val h = m.h
        for (y in 0 until outH) {
            val v = (y + 0.5) / outH
            val row = y * outW
            for (x in 0 until outW) {
                val u = (x + 0.5) / outW
                val sx = (1 - v) * tx[x] + v * bx[x] + (1 - u) * lx[y] + u * rx[y] - u
                val sy = (1 - v) * ty[x] + v * by[x] + (1 - u) * ly[y] + u * ry[y] - v
                val d = h[6] * sx + h[7] * sy + 1.0
                val px = (h[0] * sx + h[1] * sy + h[2]) / d
                val py = (h[3] * sx + h[4] * sy + h[5]) / d
                out[row + x] = sample(src, sw, sh, px, py)
            }
        }
        return out
    }

    /**
     * Zamalowuje resztki tła (blatu) przy krawędziach wyprostowanej strony kolorem papieru:
     * ciemne lub kolorowe piksele połączone z brzegiem, w pasie do 4% szerokości.
     */
    fun cleanBorders(px: IntArray, w: Int, h: Int) {
        val n = w * h
        fun lum(c: Int) = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8
        fun sat(c: Int): Int {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return maxOf(r, g, b) - minOf(r, g, b)
        }
        // kolor papieru: jasne piksele środkowej części strony
        val lums = ArrayList<Int>()
        for (y in h / 4 until h * 3 / 4 step 4) for (x in w / 4 until w * 3 / 4 step 4) lums.add(lum(px[y * w + x]))
        if (lums.isEmpty()) return
        lums.sort()
        val paperLum = lums[lums.size * 9 / 10]
        var sr = 0L; var sg = 0L; var sb = 0L; var k = 0
        for (y in h / 4 until h * 3 / 4 step 4) for (x in w / 4 until w * 3 / 4 step 4) {
            val c = px[y * w + x]
            if (lum(c) >= paperLum - 10) { sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; k++ }
        }
        if (k == 0) return
        val paper = (0xFF shl 24) or ((sr / k).toInt() shl 16) or ((sg / k).toInt() shl 8) or (sb / k).toInt()
        val thr = paperLum * 55 / 100
        val band = max(2, min(w, h) * 4 / 100)
        fun isBg(i: Int): Boolean { val c = px[i]; return lum(c) < thr || sat(c) > 60 }
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0; var tail = 0
        fun push(i: Int) { if (!seen[i] && isBg(i)) { seen[i] = true; queue[tail++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val p = queue[head++]
            val x = p % w; val y = p / w
            if (min(min(x, w - 1 - x), min(y, h - 1 - y)) >= band) continue
            px[p] = paper
            if (x > 0) push(p - 1); if (x < w - 1) push(p + 1)
            if (y > 0) push(p - w); if (y < h - 1) push(p + w)
        }
    }

    private fun sample(src: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
        if (x < -1 || y < -1 || x > w + 1 || y > h + 1) return -1 // poza zdjęciem: biel
        val fx = (x - 0.5).coerceIn(0.0, w - 1.0)
        val fy = (y - 0.5).coerceIn(0.0, h - 1.0)
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val ax = ((fx - x0) * 256).toInt(); val ay = ((fy - y0) * 256).toInt()
        val c00 = src[y0 * w + x0]; val c10 = src[y0 * w + x1]; val c01 = src[y1 * w + x0]; val c11 = src[y1 * w + x1]
        var res = 0xFF shl 24
        for (s in intArrayOf(16, 8, 0)) {
            val top = ((c00 shr s) and 0xFF) * (256 - ax) + ((c10 shr s) and 0xFF) * ax
            val bot = ((c01 shr s) and 0xFF) * (256 - ax) + ((c11 shr s) and 0xFF) * ax
            res = res or (((top * (256 - ay) + bot * ay) shr 16) shl s)
        }
        return res
    }

    /** Odwzorowanie (u, v) ∈ [0,1]² → punkt na zdjęciu. */
    private class Mapper(f: PageFrame) {
        val h = squareToQuad(
            f.x(0).toDouble(), f.y(0).toDouble(), f.x(2).toDouble(), f.y(2).toDouble(),
            f.x(4).toDouble(), f.y(4).toDouble(), f.x(6).toDouble(), f.y(6).toDouble()
        )
        private val inv = invert(h)
        // środki krawędzi w przestrzeni wyprostowanej → punkty kontrolne krzywych Béziera
        private val ctrl = DoubleArray(8).also { c ->
            val chordMid = arrayOf(doubleArrayOf(0.5, 0.0), doubleArrayOf(1.0, 0.5), doubleArrayOf(0.5, 1.0), doubleArrayOf(0.0, 0.5))
            val midIdx = intArrayOf(1, 3, 5, 7)
            for (e in 0 until 4) {
                val mx = f.x(midIdx[e]).toDouble(); val my = f.y(midIdx[e]).toDouble()
                val d = inv[6] * mx + inv[7] * my + inv[8]
                val ux = (inv[0] * mx + inv[1] * my + inv[2]) / d
                val uy = (inv[3] * mx + inv[4] * my + inv[5]) / d
                c[2 * e] = 2 * ux - chordMid[e][0]
                c[2 * e + 1] = 2 * uy - chordMid[e][1]
            }
        }
        // krawędzie: 0 góra (0,0)→(1,0), 1 prawa (1,0)→(1,1), 2 dół (0,1)→(1,1), 3 lewa (0,0)→(0,1)
        private val ends = arrayOf(
            doubleArrayOf(0.0, 0.0, 1.0, 0.0), doubleArrayOf(1.0, 0.0, 1.0, 1.0),
            doubleArrayOf(0.0, 1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        )

        fun edge(e: Int, t: Double): DoubleArray {
            val a = ends[e]
            val cx = ctrl[2 * e]; val cy = ctrl[2 * e + 1]
            val s = 1 - t
            return doubleArrayOf(s * s * a[0] + 2 * s * t * cx + t * t * a[2], s * s * a[1] + 2 * s * t * cy + t * t * a[3])
        }

        fun map(u: Double, v: Double): DoubleArray {
            val t = edge(0, u); val b = edge(2, u); val l = edge(3, v); val r = edge(1, v)
            val sx = (1 - v) * t[0] + v * b[0] + (1 - u) * l[0] + u * r[0] - u
            val sy = (1 - v) * t[1] + v * b[1] + (1 - u) * l[1] + u * r[1] - v
            val d = h[6] * sx + h[7] * sy + 1.0
            return doubleArrayOf((h[0] * sx + h[1] * sy + h[2]) / d, (h[3] * sx + h[4] * sy + h[5]) / d)
        }
    }

    /** Homografia kwadratu jednostkowego na czworokąt (Heckbert). Zwraca [a b c d e f g h 1]. */
    private fun squareToQuad(
        x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double
    ): DoubleArray {
        val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x0 - x1 + x2 - x3
        val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y0 - y1 + y2 - y3
        if (abs(dx3) < 1e-9 && abs(dy3) < 1e-9) {
            return doubleArrayOf(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0.0, 0.0, 1.0)
        }
        val det = dx1 * dy2 - dx2 * dy1
        val g = (dx3 * dy2 - dx2 * dy3) / det
        val hh = (dx1 * dy3 - dx3 * dy1) / det
        return doubleArrayOf(
            x1 - x0 + g * x1, x3 - x0 + hh * x3, x0,
            y1 - y0 + g * y1, y3 - y0 + hh * y3, y0,
            g, hh, 1.0
        )
    }

    private fun invert(m: DoubleArray): DoubleArray {
        val (a, b, c) = Triple(m[0], m[1], m[2])
        val (d, e, f) = Triple(m[3], m[4], m[5])
        val (g, h, i) = Triple(m[6], m[7], m[8])
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        return doubleArrayOf(
            A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            C / det, -(a * h - b * g) / det, (a * e - b * d) / det
        )
    }
}
