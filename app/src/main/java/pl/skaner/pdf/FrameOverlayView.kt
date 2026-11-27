package pl.skaner.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Nakładka na podgląd aparatu: zarys wykrytej kartki (kadr znormalizowany 0..1). */
class FrameOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var shown: FloatArray? = null
    private var imageAspect = 3f / 4f
    private var misses = 0

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.rgb(79, 195, 247)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 79, 195, 247) }
    private val path = Path()

    /** [frame] null = nie wykryto kartki w tej klatce. [aspect] = szerokość/wysokość obrazu. */
    fun update(frame: PageFrame?, aspect: Float) {
        imageAspect = aspect
        if (frame == null) {
            if (++misses > 4) shown = null
        } else {
            misses = 0
            val cur = shown
            // wygładzanie, żeby ramka nie „skakała”
            shown = if (cur == null) frame.pts.copyOf() else FloatArray(16) { cur[it] * 0.5f + frame.pts[it] * 0.5f }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = shown ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        // obszar obrazu przy skalowaniu „fit center”
        val iw: Float; val ih: Float
        if (vw / vh > imageAspect) { ih = vh; iw = vh * imageAspect } else { iw = vw; ih = vw / imageAspect }
        val ox = (vw - iw) / 2; val oy = (vh - ih) / 2
        fun x(i: Int) = ox + p[2 * i] * iw
        fun y(i: Int) = oy + p[2 * i + 1] * ih
        path.reset()
        path.moveTo(x(0), y(0))
        for (e in 0 until 4) {
            val a = 2 * e; val m = a + 1; val b = (a + 2) % 8
            path.quadTo(2 * x(m) - (x(a) + x(b)) / 2, 2 * y(m) - (y(a) + y(b)) / 2, x(b), y(b))
        }
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }
}
