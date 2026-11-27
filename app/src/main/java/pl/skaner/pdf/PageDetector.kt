package pl.skaner.pdf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Wynik wykrywania: kadr (w pikselach obrazu wejściowego) i pewność 0..1. */
class Detection(val frame: PageFrame, val confidence: Double)

/**
 * Wykrywanie kartki na zdjęciu.
 *
 * Etap zgrubny (obraz ≤ 800 px):
 *  1. kanały: jasność + dwa kanały koloru (kartka różni się od tła jasnością LUB barwą),
 *  2. krawędzie Canny'ego (gradient z kanału o największej różnicy),
 *  3. transformata Hougha → najsilniejsze proste,
 *  4. czworokąty z par prawie równoległych prostych (+ brzegi kadru, + kandydat z segmentacji),
 *     oceniane: pokrycie boków krawędziami, kontrast wnętrze/zewnątrz, spójność wnętrza, pole.
 * Etap dokładny (obraz pełny / ~1600 px):
 *  5. dla każdego boku szukanie brzegu wzdłuż normalnej w ~40 miejscach (subpiksel),
 *     odporne dopasowanie krzywej 2. stopnia (prosta + wygięcie), rogi z przecięcia stycznych.
 */
object PageDetector {

    private const val CHROMA_W = 1.6f
    private const val MIN_CONFIDENCE = 0.22

    /** Diagnostyka (tylko testy). */
    var debug: ((String) -> Unit)? = null

    // ------------------------------------------------------------------ API

    /** Pełne wykrywanie na obrazie kolorowym (zalecane ~1200–2000 px). */
    fun detect(px: IntArray, w: Int, h: Int): Detection? {
        val f = max(1, ceil(max(w, h) / 800.0).toInt())
        val sw = w / f; val sh = h / f
        val small = if (f == 1) px else downsample(px, w, h, f)
        val coarse = coarse(Channels.fromArgb(small, sw, sh)) ?: return null
        val quad = coarse.corners.map { doubleArrayOf(it[0] * f, it[1] * f) }
        val ch = Channels.fromArgb(px, w, h).blurred(1, 1)
        return refine(ch, quad, coarse.sideChannel, coarse.sideSign, coarse.score, coarse.borderSide)
    }

    /** Obraz małego formatu w dwóch rozdzielczościach (np. w testach). */
    fun detectFull(small: IntArray, sw: Int, sh: Int, full: IntArray, w: Int, h: Int): Detection? {
        val coarse = coarse(Channels.fromArgb(small, sw, sh)) ?: return null
        val sx = w.toDouble() / sw; val sy = h.toDouble() / sh
        val quad = coarse.corners.map { doubleArrayOf(it[0] * sx, it[1] * sy) }
        val ch = Channels.fromArgb(full, w, h).blurred(1, 1)
        return refine(ch, quad, coarse.sideChannel, coarse.sideSign, coarse.score, coarse.borderSide)
    }

    /** Szybkie wykrywanie na samej jasności (podgląd z kamery na żywo). */
    fun detectLuma(lum: IntArray, w: Int, h: Int): PageFrame? {
        val c = coarse(Channels(w, h, arrayOf(lum.copyOf()))) ?: return null
        return frameFromCorners(c.corners)
    }

    // ------------------------------------------------------------------ kanały

    private class Channels(val w: Int, val h: Int, val c: Array<IntArray>) {
        fun blurred(r: Int, passes: Int): Channels {
            val n = w * h
            val tmp = IntArray(n)
            return Channels(w, h, Array(c.size) { k ->
                var src = c[k]
                repeat(passes) { val dst = IntArray(n); EnhancerCore.boxBlur(src, dst, tmp, w, h, r); src = dst }
                src
            })
        }

        fun weight(k: Int) = if (k == 0) 1f else CHROMA_W

        companion object {
            fun fromArgb(px: IntArray, w: Int, h: Int): Channels {
                val n = w * h
                val l = IntArray(n); val cb = IntArray(n); val cr = IntArray(n)
                for (i in 0 until n) {
                    val p = px[i]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    val y = (77 * r + 150 * g + 29 * b) shr 8
                    l[i] = y; cb[i] = 128 + (b - y) / 2; cr[i] = 128 + (r - y) / 2
                }
                return Channels(w, h, arrayOf(l, cb, cr))
            }
        }
    }

    private fun downsample(px: IntArray, w: Int, h: Int, f: Int): IntArray {
        val sw = w / f; val sh = h / f
        val out = IntArray(sw * sh)
        val nn = f * f
        for (y in 0 until sh) for (x in 0 until sw) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0 until f) {
                val row = (y * f + dy) * w + x * f
                for (dx in 0 until f) { val p = px[row + dx]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
            }
            out[y * sw + x] = (0xFF shl 24) or ((r / nn) shl 16) or ((g / nn) shl 8) or (b / nn)
        }
        return out
    }

    // ------------------------------------------------------------------ etap zgrubny

    private class Line(val theta: Double, val rho: Double, val votes: Int, val border: Boolean = false) {
        val nx = cos(theta); val ny = sin(theta)
    }

    private class Coarse(
        val corners: List<DoubleArray>, val score: Double,
        val sideChannel: IntArray, val sideSign: IntArray, val borderSide: BooleanArray, val info: String = ""
    )

    private class Edges(val w: Int, val h: Int, val edge: BooleanArray, val gx: FloatArray, val gy: FloatArray)

    private fun coarse(raw: Channels): Coarse? {
        val w = raw.w; val h = raw.h
        if (w < 32 || h < 32) return null
        val r = max(1, max(w, h) / 400)
        val ch = raw.blurred(r, 2)
        val edges = canny(ch)
        val lines = hough(edges)
        val candidates = ArrayList<List<DoubleArray>>()
        quadsFromLines(lines, w, h, candidates)
        segmentQuad(raw)?.let { candidates.add(it) }
        if (candidates.isEmpty()) return null

        var best: Coarse? = null
        val all = ArrayList<Coarse>()
        for (q in candidates) {
            val s = scoreQuad(q, ch, edges) ?: continue
            all.add(s)
            if (best == null || s.score > best.score) best = s
        }
        debug?.let { d ->
            d("linie: ${lines.size}, kandydaci: ${candidates.size}")
            lines.filter { !it.border }.forEach { l -> d(String.format("   th=%.0f rho=%.0f g=%d", l.theta * 180 / PI, l.rho, l.votes)) }
            all.sortedByDescending { it.score }.take(6).forEach { c ->
                d(String.format("  %.3f  %s  %s", c.score, c.corners.joinToString(" ") { "(%.0f,%.0f)".format(it[0], it[1]) }, c.info))
            }
        }
        return best?.takeIf { it.score >= MIN_CONFIDENCE }
    }

    private fun canny(ch: Channels): Edges {
        val w = ch.w; val h = ch.h; val n = w * h
        val gx = FloatArray(n); val gy = FloatArray(n); val mag = FloatArray(n)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            var bm = -1f; var bx = 0f; var by = 0f
            for (k in ch.c.indices) {
                val c = ch.c[k]; val wt = ch.weight(k)
                val sx = ((c[i - w + 1] + 2 * c[i + 1] + c[i + w + 1]) - (c[i - w - 1] + 2 * c[i - 1] + c[i + w - 1])) * wt
                val sy = ((c[i + w - 1] + 2 * c[i + w] + c[i + w + 1]) - (c[i - w - 1] + 2 * c[i - w] + c[i - w + 1])) * wt
                val m = sx * sx + sy * sy
                if (m > bm) { bm = m; bx = sx; by = sy }
            }
            gx[i] = bx; gy[i] = by; mag[i] = sqrt(bm)
        }
        // tłumienie niemaksymalnych
        val nms = FloatArray(n)
        val kept = ArrayList<Float>()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]; if (m < 4f) continue
            val ax = abs(gx[i]); val ay = abs(gy[i])
            val (a, b) = when {
                ay <= 0.4142f * ax -> i - 1 to i + 1
                ax <= 0.4142f * ay -> i - w to i + w
                gx[i] * gy[i] > 0 -> i - w - 1 to i + w + 1
                else -> i - w + 1 to i + w - 1
            }
            if (m >= mag[a] && m > mag[b]) { nms[i] = m; kept.add(m) }
        }
        val edge = BooleanArray(n)
        if (kept.isEmpty()) return Edges(w, h, edge, gx, gy)
        // progi liczone z dala od tekstu: gęste skupiska krawędzi (litery) zawyżałyby próg
        // i gubiły słabe brzegi kartki (biel na bieli, cień)
        val dens = IntArray(n) { if (nms[it] >= 8f) 255 else 0 }
        val densB = IntArray(n); val tmpD = IntArray(n)
        EnhancerCore.boxBlur(dens, densB, tmpD, w, h, max(3, max(w, h) / 100))
        val sparse = ArrayList<Float>()
        for (i in 0 until n) if (nms[i] >= 4f && densB[i] < 60) sparse.add(nms[i])
        val pool = if (sparse.size > 200) sparse else kept
        pool.sort()
        val high = max(10f, pool[(pool.size * 0.70).toInt().coerceAtMost(pool.size - 1)])
        val low = high * 0.4f
        debug?.invoke(String.format("canny: high=%.1f low=%.1f (pula %d, rzadkie %d)", high, low, pool.size, sparse.size))
        val queue = IntArray(n); var head = 0; var tail = 0
        for (i in 0 until n) if (nms[i] >= high) { edge[i] = true; queue[tail++] = i }
        while (head < tail) {
            val p = queue[head++]
            val x = p % w; val y = p / w
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx < 1 || yy < 1 || xx >= w - 1 || yy >= h - 1) continue
                val q = yy * w + xx
                if (!edge[q] && nms[q] >= low) { edge[q] = true; queue[tail++] = q }
            }
        }
        return Edges(w, h, edge, gx, gy)
    }

    private fun hough(e: Edges): List<Line> {
        val w = e.w; val h = e.h
        val d = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
        val nr = 2 * d + 1
        val acc = IntArray(180 * nr)
        val cs = DoubleArray(180) { cos(it * PI / 180) }
        val sn = DoubleArray(180) { sin(it * PI / 180) }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!e.edge[i]) continue
            var phi = atan2(e.gy[i].toDouble(), e.gx[i].toDouble()) * 180 / PI
            if (phi < 0) phi += 180.0
            if (phi >= 180) phi -= 180.0
            val t0 = phi.roundToInt()
            val fx = x + 0.5; val fy = y + 0.5
            for (dt in -3..3) {
                val t = ((t0 + dt) % 180 + 180) % 180
                val rho = fx * cs[t] + fy * sn[t]
                acc[t * nr + rho.roundToInt() + d]++
            }
        }
        val minVotes = max(10, (min(w, h) * 0.07).toInt())
        val peaks = ArrayList<IntArray>()
        for (t in 0 until 180) for (r in 2 until nr - 2) {
            val v = acc[t * nr + r]
            if (v < minVotes) continue
            var isMax = true
            loop@ for (dt in -2..2) {
                val tt = t + dt; if (tt < 0 || tt >= 180) continue
                for (dr in -2..2) {
                    if (dt == 0 && dr == 0) continue
                    val o = acc[tt * nr + r + dr]
                    if (o > v || (o == v && (dt < 0 || (dt == 0 && dr < 0)))) { isMax = false; break@loop }
                }
            }
            if (isMax) peaks.add(intArrayOf(t, r - d, v))
        }
        peaks.sortByDescending { it[2] }
        val lines = ArrayList<Line>()
        val minDist = max(8.0, d * 0.02)
        for (p in peaks) {
            val th = p[0] * PI / 180; val rho = p[1].toDouble()
            val similar = lines.any { l ->
                var dth = abs(l.theta - th); var r2 = rho
                if (dth > PI / 2) { dth = PI - dth; r2 = -rho }
                dth < 5 * PI / 180 && abs(l.rho - r2) < minDist
            }
            if (similar) continue
            lines.add(Line(th, rho, p[2]))
            if (lines.size >= (if (max(w, h) <= 400) 18 else 36)) break
        }
        // brzegi kadru – gdy kartka wychodzi poza zdjęcie
        lines.add(Line(0.0, 0.0, 0, true)); lines.add(Line(0.0, w.toDouble(), 0, true))
        lines.add(Line(PI / 2, 0.0, 0, true)); lines.add(Line(PI / 2, h.toDouble(), 0, true))
        return lines
    }

    private fun quadsFromLines(lines: List<Line>, w: Int, h: Int, out: MutableList<List<DoubleArray>>) {
        val cx = w / 2.0; val cy = h / 2.0
        val minSep = min(w, h) * 0.2
        class Pair(val a: Line, val b: Line, val dir: Double, val strength: Int)
        val pairs = ArrayList<Pair>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i]; val b = lines[j]
            if (a.border && b.border && !(a.theta == b.theta)) continue
            var dth = abs(a.theta - b.theta)
            if (dth > PI / 2) dth = PI - dth
            if (dth > 30 * PI / 180) continue
            // odległość prostych w okolicy środka obrazu
            val da = a.nx * cx + a.ny * cy - a.rho
            val db = b.nx * cx + b.ny * cy - b.rho
            val sep = abs(da * sign(a, b) - db)
            if (sep < minSep) continue
            pairs.add(Pair(a, b, a.theta, a.votes + b.votes))
        }
        pairs.sortByDescending { it.strength }
        val top = pairs.take(if (max(w, h) <= 400) 30 else 70)
        for (i in top.indices) for (j in i + 1 until top.size) {
            val p = top[i]; val q = top[j]
            var dd = abs(p.dir - q.dir) % PI
            if (dd > PI / 2) dd = PI - dd
            if (dd < 45 * PI / 180) continue
            val c = listOf(
                intersect(p.a, q.a), intersect(p.a, q.b), intersect(p.b, q.b), intersect(p.b, q.a)
            )
            if (c.any { it == null }) continue
            val pts = orderCorners(c.map { it!! }) ?: continue
            if (!validQuad(pts, w, h)) continue
            out.add(pts)
        }
    }

    /** Znak pozwalający porównać ρ dwóch prostych o prawie przeciwnych kątach. */
    private fun sign(a: Line, b: Line) = if (abs(a.theta - b.theta) > PI / 2) -1.0 else 1.0

    private fun intersect(a: Line, b: Line): DoubleArray? {
        val det = a.nx * b.ny - a.ny * b.nx
        if (abs(det) < 1e-6) return null
        return doubleArrayOf((a.rho * b.ny - a.ny * b.rho) / det, (a.nx * b.rho - a.rho * b.nx) / det)
    }

    private fun orderCorners(c: List<DoubleArray>): List<DoubleArray>? {
        val cx = c.sumOf { it[0] } / 4; val cy = c.sumOf { it[1] } / 4
        val sorted = c.sortedBy { atan2(it[1] - cy, it[0] - cx) }
        val tl = sorted.indices.minBy { sorted[it][0] + sorted[it][1] }
        return List(4) { sorted[(tl + it) % 4] }
    }

    private fun validQuad(p: List<DoubleArray>, w: Int, h: Int): Boolean {
        val mx = w * 0.15; val my = h * 0.15
        if (p.any { it[0] < -mx || it[0] > w + mx || it[1] < -my || it[1] > h + my }) return false
        // wypukłość i kąty 40°–140°
        var sgn = 0
        for (k in 0 until 4) {
            val a = p[(k + 3) % 4]; val b = p[k]; val c = p[(k + 1) % 4]
            val v1x = a[0] - b[0]; val v1y = a[1] - b[1]; val v2x = c[0] - b[0]; val v2y = c[1] - b[1]
            val cr = v1x * v2y - v1y * v2x
            val s = if (cr > 0) 1 else -1
            if (sgn == 0) sgn = s else if (s != sgn) return false
            val cosA = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-9)
            if (abs(cosA) > 0.766) return false
        }
        return area(p) >= w * h * 0.08
    }

    private fun area(p: List<DoubleArray>): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a[0] * b[1] - b[0] * a[1] }
        return abs(s) / 2
    }

    private fun sample(c: IntArray, w: Int, h: Int, x: Double, y: Double): Double {
        val fx = (x - 0.5).coerceIn(0.0, w - 1.001); val fy = (y - 0.5).coerceIn(0.0, h - 1.001)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val ax = fx - x0; val ay = fy - y0
        val i = y0 * w + x0
        val top = c[i] * (1 - ax) + c[i + 1] * ax
        val bot = c[i + w] * (1 - ax) + c[i + w + 1] * ax
        return top * (1 - ay) + bot * ay
    }

    private fun onBorder(a: DoubleArray, b: DoubleArray, w: Int, h: Int): Boolean {
        val tx = w * 0.012; val ty = h * 0.012
        return (a[0] < tx && b[0] < tx) || (a[0] > w - tx && b[0] > w - tx) ||
            (a[1] < ty && b[1] < ty) || (a[1] > h - ty && b[1] > h - ty)
    }

    /** Ocena kandydata. */
    private fun scoreQuad(q: List<DoubleArray>, ch: Channels, e: Edges): Coarse? {
        val w = ch.w; val h = ch.h
        val cx = q.sumOf { it[0] } / 4; val cy = q.sumOf { it[1] } / 4
        val sideChannel = IntArray(4); val sideSign = IntArray(4); val border = BooleanArray(4)
        val insideLum = DoubleArray(4); val diffLum = DoubleArray(4)
        val sideSupport = DoubleArray(4); val sideContrast = DoubleArray(4)
        val sideIn = Array(4) { DoubleArray(ch.c.size) }; val sideFar = Array(4) { DoubleArray(ch.c.size) }
        val measured = BooleanArray(4)
        var total = 0.0
        val info = StringBuilder()
        val off = max(3.0, max(w, h) / 160.0)
        for (k in 0 until 4) {
            val a = q[k]; val b = q[(k + 1) % 4]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            var nx = -(b[1] - a[1]) / len; var ny = (b[0] - a[0]) / len
            val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
            if (nx * (mx - cx) + ny * (my - cy) < 0) { nx = -nx; ny = -ny }
            if (onBorder(a, b, w, h)) {
                border[k] = true
                var sum = 0.0; var cnt = 0
                for (s in 0 until 24) {
                    val t = 0.1 + 0.8 * s / 23
                    sum += sample(ch.c[0], w, h, a[0] + (b[0] - a[0]) * t - nx * off * 2, a[1] + (b[1] - a[1]) * t - ny * off * 2); cnt++
                }
                insideLum[k] = Double.NaN
                diffLum[k] = sum / cnt // tymczasowo: jasność wnętrza przy brzegu kadru
                continue
            }
            val m = (len / 3).toInt().coerceIn(16, 120)
            var support = 0; var valid = 0
            val inSum = DoubleArray(ch.c.size); val outSum = DoubleArray(ch.c.size); val farSum = DoubleArray(ch.c.size)
            for (s in 0 until m) {
                val t = 0.04 + 0.92 * s / (m - 1)
                val px = a[0] + (b[0] - a[0]) * t; val py = a[1] + (b[1] - a[1]) * t
                if (px < 1 || py < 1 || px > w - 1 || py > h - 1) continue
                valid++
                var ok = false
                // dopuszczalne odchylenie: 1,6 px na końcach, do ~4% długości boku w środku (wygięta kartka)
                val tol = 1.6 + 0.04 * len * sin(PI * t)
                val steps = (tol / 0.8).toInt()
                for (j in 0..2 * steps) {
                    val o = if (j % 2 == 0) j / 2 * 0.8 else -(j + 1) / 2 * 0.8
                    val xi = (px + nx * o).toInt(); val yi = (py + ny * o).toInt()
                    if (xi < 0 || yi < 0 || xi >= w || yi >= h) continue
                    val i = yi * w + xi
                    if (!e.edge[i]) continue
                    val g = hypot(e.gx[i].toDouble(), e.gy[i].toDouble())
                    if (abs(e.gx[i] * nx + e.gy[i] * ny) / (g + 1e-9) > 0.88) { ok = true; break }
                }
                if (ok) support++
                for (c in ch.c.indices) {
                    inSum[c] += sample(ch.c[c], w, h, px - nx * off, py - ny * off)
                    outSum[c] += sample(ch.c[c], w, h, px + nx * off, py + ny * off)
                    farSum[c] += sample(ch.c[c], w, h, px + nx * off * 3, py + ny * off * 3)
                }
            }
            if (valid < m / 2) { total += 0.0; insideLum[k] = Double.NaN; continue }
            var bestC = 0; var bestD = 0.0; var dist2 = 0.0
            for (c in ch.c.indices) {
                val d = (outSum[c] - inSum[c]) / valid * ch.weight(c)
                dist2 += d * d
                if (abs(d) > abs(bestD)) { bestD = d; bestC = c }
            }
            sideChannel[k] = bestC; sideSign[k] = if (bestD >= 0) 1 else -1
            measured[k] = true
            insideLum[k] = inSum[0] / valid; diffLum[k] = (outSum[0] - inSum[0]) / valid
            val contrast = sqrt(dist2)
            val frac = support.toDouble() / valid
            sideSupport[k] = frac; sideContrast[k] = contrast
            for (c in ch.c.indices) { sideIn[k][c] = inSum[c] / valid; sideFar[k][c] = farSum[c] / valid }
        }
        // kolor kartki = średnia z pasów wewnętrznych; boki oceniane po różnicy „tło za brzegiem – kartka”
        // (linia tabeli czy tekstu wewnątrz kartki ma po obu stronach papier → mała różnica)
        var minSide = 1.0
        val paper = DoubleArray(ch.c.size)
        val nm = measured.count { it }
        if (nm > 0) for (k in 0 until 4) if (measured[k]) for (c in ch.c.indices) paper[c] += sideIn[k][c] / nm
        for (k in 0 until 4) if (measured[k]) {
            var d2 = 0.0
            for (c in ch.c.indices) { val d = (sideFar[k][c] - paper[c]) * ch.weight(c); d2 += d * d }
            val cOut = sqrt(d2)
            val c = min(sideContrast[k], cOut + 6)
            val sc = sideSupport[k].pow(3) * (0.55 + 0.45 * min(1.0, c / 12.0))
            total += sc; minSide = min(minSide, sc)
            info.append(String.format("[s%.2f c%.0f o%.0f]", sideSupport[k], sideContrast[k], cOut))
        }
        // boki na brzegu kadru: częściowy kredyt, gdy przy brzegu jest „ta sama kartka”
        val ins = insideLum.filter { !it.isNaN() }
        val meanIn = if (ins.isEmpty()) Double.NaN else ins.average()
        for (k in 0 until 4) if (border[k]) {
            val credit = if (!meanIn.isNaN() && abs(diffLum[k] - meanIn) < 30) 0.4 else 0.05
            total += credit; minSide = min(minSide, credit + 0.35)
            info.append(String.format("[brzeg %.2f]", credit))
        }
        // czworokąt jest tak dobry, jak jego najsłabszy bok
        var score = total / 4 * (0.5 + 0.5 * minSide.coerceAtMost(1.0))
        // spójność: wnętrze przy wszystkich bokach powinno wyglądać podobnie (to ta sama kartka)
        if (ins.size >= 2 && ins.max() - ins.min() > 90) score *= 0.7
        // większe kandydaty (cała kartka, nie blok tekstu czy tabela) mają przewagę
        score *= (area(q) / (w * h)).coerceAtMost(1.0).pow(0.25)
        if (border.count { it } >= 3) score *= 0.3
        return Coarse(q, score, sideChannel, sideSign, border, info.toString())
    }

    // ------------------------------------------------------------------ kandydat z segmentacji

    /** Rogi największego jasnego, mało kolorowego obszaru (próg Otsu) – dodatkowy kandydat. */
    private fun segmentQuad(ch: Channels): List<DoubleArray>? {
        val w = ch.w; val h = ch.h; val n = w * h
        val l = ch.c[0]
        val score = if (ch.c.size == 3) IntArray(n) {
            val sat = (abs(ch.c[1][it] - 128) + abs(ch.c[2][it] - 128)) * 2
            (l[it] - sat).coerceIn(0, 255)
        } else l.copyOf()
        val tmp = IntArray(n); val blurred = IntArray(n)
        EnhancerCore.boxBlur(score, blurred, tmp, w, h, 2)
        val hist = IntArray(256)
        for (v in blurred) hist[v]++
        var sumAll = 0.0
        for (i in 0 until 256) sumAll += i.toDouble() * hist[i]
        var wB = 0.0; var sumB = 0.0; var bestV = -1.0; var thr = 128
        for (t in 0 until 256) {
            wB += hist[t]; if (wB == 0.0) continue
            val wF = n - wB; if (wF == 0.0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > bestV) { bestV = between; thr = t }
        }
        val mask = BooleanArray(n) { blurred[it] > thr }
        val label = IntArray(n); val queue = IntArray(n)
        var bestLabel = 0; var bestSize = 0; var cur = 0
        for (s in 0 until n) {
            if (!mask[s] || label[s] != 0) continue
            cur++
            var head = 0; var tail = 0
            queue[tail++] = s; label[s] = cur
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                if (x > 0 && mask[p - 1] && label[p - 1] == 0) { label[p - 1] = cur; queue[tail++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && label[p + 1] == 0) { label[p + 1] = cur; queue[tail++] = p + 1 }
                if (y > 0 && mask[p - w] && label[p - w] == 0) { label[p - w] = cur; queue[tail++] = p - w }
                if (y < h - 1 && mask[p + w] && label[p + w] == 0) { label[p + w] = cur; queue[tail++] = p + w }
            }
            if (tail > bestSize) { bestSize = tail; bestLabel = cur }
        }
        if (bestSize < n * 0.08) return null
        val bx = ArrayList<Int>(); val by = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (label[i] != bestLabel) continue
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1 || label[i - 1] != bestLabel || label[i + 1] != bestLabel ||
                label[i - w] != bestLabel || label[i + w] != bestLabel) { bx.add(x); by.add(y) }
        }
        var hull = convexHull(bx, by)
        if (hull.size < 4) return null
        if (hull.size > 120) { val step = hull.size / 120.0; hull = List(120) { hull[(it * step).toInt()] } }
        val q = maxAreaQuad(hull) ?: return null
        return orderCorners(q.map { doubleArrayOf(it[0] + 0.5, it[1] + 0.5) })
    }

    private fun convexHull(xs: List<Int>, ys: List<Int>): List<DoubleArray> {
        val pts = xs.indices.map { doubleArrayOf(xs[it].toDouble(), ys[it].toDouble()) }
            .sortedWith(compareBy({ it[0] }, { it[1] }))
        if (pts.size < 3) return pts
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<DoubleArray>()
        for (p in pts) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1); lower.add(p) }
        val upper = ArrayList<DoubleArray>()
        for (p in pts.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1); upper.add(p) }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun maxAreaQuad(hull: List<DoubleArray>): List<DoubleArray>? {
        val n = hull.size
        fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray) =
            abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) / 2
        var best = -1.0; var res: IntArray? = null
        for (i in 0 until n) for (j in i + 2 until n) {
            if (i == 0 && j == n - 1) continue
            var a1 = -1.0; var k1 = -1
            for (k in i + 1 until j) { val a = tri(hull[i], hull[k], hull[j]); if (a > a1) { a1 = a; k1 = k } }
            var a2 = -1.0; var k2 = -1
            var k = (j + 1) % n
            while (k != i) { val a = tri(hull[i], hull[k], hull[j]); if (a > a2) { a2 = a; k2 = k }; k = (k + 1) % n }
            if (k1 >= 0 && k2 >= 0 && a1 + a2 > best) { best = a1 + a2; res = intArrayOf(i, k1, j, k2) }
        }
        return res?.map { hull[it] }
    }

    // ------------------------------------------------------------------ etap dokładny

    /** Krzywa boku w układzie lokalnym: odsunięcie d(t) = a + b·t + c·t², t ∈ [0, len]. */
    private class SideFit(
        val ax: Double, val ay: Double, val ux: Double, val uy: Double, val nx: Double, val ny: Double,
        val len: Double, val a: Double, val b: Double, val c: Double
    ) {
        fun point(t: Double): DoubleArray {
            val d = a + b * t + c * t * t
            return doubleArrayOf(ax + ux * t + nx * d, ay + uy * t + ny * d)
        }

        fun tangent(t: Double): DoubleArray {
            val dd = b + 2 * c * t
            val tx = ux + nx * dd; val ty = uy + ny * dd
            val l = hypot(tx, ty)
            return doubleArrayOf(tx / l, ty / l)
        }
    }

    private fun refine(
        ch: Channels, quad: List<DoubleArray>, sideChannel: IntArray, sideSign: IntArray,
        coarseScore: Double, border: BooleanArray
    ): Detection {
        val w = ch.w; val h = ch.h
        val cx = quad.sumOf { it[0] } / 4; val cy = quad.sumOf { it[1] } / 4
        val diag = hypot(w.toDouble(), h.toDouble())
        val band = max(8.0, diag * 0.022)
        val fits = ArrayList<SideFit>()
        var quality = 0.0
        for (k in 0 until 4) {
            val a = quad[k]; val b = quad[(k + 1) % 4]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            val ux = (b[0] - a[0]) / len; val uy = (b[1] - a[1]) / len
            var nx = -uy; var ny = ux
            val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
            if (nx * (mx - cx) + ny * (my - cy) < 0) { nx = -nx; ny = -ny }
            if (border[k]) {
                fits.add(SideFit(a[0], a[1], ux, uy, nx, ny, len, 0.0, 0.0, 0.0)); quality += 0.5; continue
            }
            val c = ch.c[sideChannel[k].coerceAtMost(ch.c.size - 1)]
            val sgn = sideSign[k]
            var fit: DoubleArray? = null
            var curBand = band
            for (pass in 0 until 2) {
                val prevFit = fit
                val ts = ArrayList<Double>(); val ds = ArrayList<Double>()
                val samples = 48
                for (s in 0 until samples) {
                    val t = len * (0.02 + 0.96 * s / (samples - 1))
                    // środek przeszukiwania: prosta zgrubna albo krzywa z poprzedniego przebiegu
                    val base = if (prevFit == null) 0.0 else prevFit[0] + prevFit[1] * t + prevFit[2] * t * t
                    val px = a[0] + ux * t + nx * base; val py = a[1] + uy * t + ny * base
                    val e = edgeAlong(c, w, h, px, py, nx, ny, curBand, sgn)
                    if (!e.isNaN()) { ts.add(t); ds.add(base + e) }
                }
                fit = robustQuadratic(ts, ds, len) ?: prevFit
                curBand = max(4.0, band * 0.5)
                if (fit == null) break
            }
            if (fit == null) {
                fits.add(SideFit(a[0], a[1], ux, uy, nx, ny, len, 0.0, 0.0, 0.0))
            } else {
                quality += fit[3]
                fits.add(SideFit(a[0], a[1], ux, uy, nx, ny, len, fit[0], fit[1], fit[2]))
            }
        }
        quality /= 4

        // rogi: przecięcia stycznych sąsiednich boków na ich końcach
        val corners = Array(4) { k ->
            val prev = fits[(k + 3) % 4]; val next = fits[k]
            val p1 = prev.point(prev.len); val d1 = prev.tangent(prev.len)
            val p2 = next.point(0.0); val d2 = next.tangent(0.0)
            val det = d1[0] * (-d2[1]) - d1[1] * (-d2[0])
            if (abs(det) < 1e-6) doubleArrayOf((p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2)
            else {
                val rx = p2[0] - p1[0]; val ry = p2[1] - p1[1]
                val t1 = (rx * (-d2[1]) - ry * (-d2[0])) / det
                doubleArrayOf(p1[0] + d1[0] * t1, p1[1] + d1[1] * t1)
            }
        }
        val pts = FloatArray(16)
        for (k in 0 until 4) {
            val cpt = corners[k]
            pts[4 * k] = cpt[0].toFloat()
            pts[4 * k + 1] = cpt[1].toFloat()
            // środek krawędzi: punkt krzywej boku w połowie
            val mid = fits[k].point(fits[k].len / 2)
            pts[4 * k + 2] = mid[0].toFloat()
            pts[4 * k + 3] = mid[1].toFloat()
        }
        // środek krzywej względem nowych rogów (gdy brak wygięcia – dokładnie w połowie cięciwy)
        val frame = PageFrame(pts)
        for (k in 0 until 4) {
            if (fits[k].c == 0.0) {
                val a = 2 * k; val b = (2 * k + 2) % 8
                frame.set(2 * k + 1, (frame.x(a) + frame.x(b)) / 2, (frame.y(a) + frame.y(b)) / 2)
            }
        }
        val conf = (coarseScore * 0.6 + quality * 0.4).coerceIn(0.0, 1.0)
        return Detection(frame, conf)
    }

    /** Położenie brzegu wzdłuż normalnej (subpiksel), względem punktu (px, py); NaN gdy brak. */
    private fun edgeAlong(c: IntArray, w: Int, h: Int, px: Double, py: Double, nx: Double, ny: Double, band: Double, sgn: Int): Double {
        if (px < 0 || py < 0 || px > w || py > h) return Double.NaN
        val n = (2 * band).toInt() + 1
        val vals = DoubleArray(n); val offs = DoubleArray(n)
        var prev = sample(c, w, h, px - nx * (band + 0.5), py - ny * (band + 0.5))
        for (i in 0 until n) {
            val o = -band + i
            val cur = sample(c, w, h, px + nx * (o + 0.5), py + ny * (o + 0.5))
            vals[i] = (cur - prev) * sgn
            offs[i] = o
            prev = cur
        }
        var bestS = Double.NaN; var bestV = 0.0
        for (i in 0 until n) {
            val prior = exp(-0.5 * (offs[i] / (band * 0.6)).pow(2))
            val v = vals[i] * prior
            if (vals[i] > 2.0 && v > bestV) {
                var sub = 0.0
                if (i > 0 && i < n - 1) {
                    val l = vals[i - 1]; val m = vals[i]; val r = vals[i + 1]
                    val den = l - 2 * m + r
                    if (abs(den) > 1e-9) sub = (0.5 * (l - r) / den).coerceIn(-0.5, 0.5)
                }
                bestV = v; bestS = offs[i] + sub
            }
        }
        return bestS
    }

    /**
     * Odporne dopasowanie d = a + b·t + c·t² (kilka iteracji z odrzucaniem odstających punktów).
     * Zwraca [a, b, c, jakość] albo null. Małe wygięcie (< 1,5 px) jest zerowane.
     */
    private fun robustQuadratic(ts: List<Double>, ds: List<Double>, len: Double): DoubleArray? {
        val n = ts.size
        if (n < 8) return null
        var use = BooleanArray(n) { true }
        var coef = doubleArrayOf(0.0, 0.0, 0.0)
        repeat(4) { iter ->
            val quad = iter >= 1
            coef = lsq(ts, ds, use, len, quad) ?: return null
            val res = DoubleArray(n) { abs(ds[it] - (coef[0] + coef[1] * ts[it] + coef[2] * ts[it] * ts[it])) }
            val sorted = res.filterIndexed { i, _ -> use[i] }.sorted()
            if (sorted.isEmpty()) return null
            val mad = sorted[sorted.size / 2]
            val lim = max(1.2, 3.0 * mad)
            use = BooleanArray(n) { res[it] <= lim }
        }
        val inl = use.count { it }
        if (inl < max(8, n / 3)) return null
        val sag = abs(coef[2]) * len * len / 4
        if (sag < 1.5) coef = lsq(ts, ds, use, len, false) ?: coef
        return doubleArrayOf(coef[0], coef[1], coef[2], inl.toDouble() / 48)
    }

    private fun lsq(ts: List<Double>, ds: List<Double>, use: BooleanArray, len: Double, quad: Boolean): DoubleArray? {
        // t normalizowane do [0,1] dla stabilności
        val m = if (quad) 3 else 2
        val ata = Array(m) { DoubleArray(m) }; val atb = DoubleArray(m)
        var cnt = 0
        for (i in ts.indices) {
            if (!use[i]) continue
            cnt++
            val u = ts[i] / len
            val row = if (quad) doubleArrayOf(1.0, u, u * u) else doubleArrayOf(1.0, u)
            for (r in 0 until m) { atb[r] += row[r] * ds[i]; for (c in 0 until m) ata[r][c] += row[r] * row[c] }
        }
        if (cnt < m + 2) return null
        val sol = solve(ata, atb) ?: return null
        return if (quad) doubleArrayOf(sol[0], sol[1] / len, sol[2] / (len * len)) else doubleArrayOf(sol[0], sol[1] / len, 0.0)
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    private fun frameFromCorners(c: List<DoubleArray>) = PageFrame.fromCorners(
        floatArrayOf(c[0][0].toFloat(), c[0][1].toFloat()), floatArrayOf(c[1][0].toFloat(), c[1][1].toFloat()),
        floatArrayOf(c[2][0].toFloat(), c[2][1].toFloat()), floatArrayOf(c[3][0].toFloat(), c[3][1].toFloat())
    )

    @Suppress("unused")
    private fun floorInt(v: Double) = floor(v).toInt()
}
