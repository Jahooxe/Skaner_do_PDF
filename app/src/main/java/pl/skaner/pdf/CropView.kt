package pl.skaner.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Edycja kadru: zdjęcie, ramka z 4 rogami i 4 punktami na środkach krawędzi (do wyginania)
 * oraz lupa pokazująca powiększenie okolicy przeciąganego punktu.
 * Kadr jest w pikselach wyświetlanej bitmapy.
 */
class CropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var bitmap: Bitmap? = null
    var frame: PageFrame? = null
        private set

    private val imgMatrix = Matrix()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private var active = -1
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var startFrame: PageFrame? = null

    private val accent = Color.rgb(79, 195, 247)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = accent }
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = accent }
    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = accent }
    private val activeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 79, 195, 247) }
    private val magBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f); color = Color.RED }

    fun setImage(bmp: Bitmap, f: PageFrame) {
        bitmap = bmp
        frame = f
        computeMatrix()
        invalidate()
    }

    fun setFrame(f: PageFrame) {
        frame = f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = computeMatrix()

    private fun computeMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val pad = dp(28f)
        scale = min((width - 2 * pad) / bmp.width, (height - 2 * pad) / bmp.height)
        offX = (width - bmp.width * scale) / 2
        offY = (height - bmp.height * scale) / 2
        imgMatrix.setScale(scale, scale)
        imgMatrix.postTranslate(offX, offY)
    }

    private fun framePath(f: PageFrame): Path {
        val p = Path()
        p.moveTo(f.x(0), f.y(0))
        for (e in 0 until 4) {
            val a = 2 * e; val m = a + 1; val b = (a + 2) % 8
            p.quadTo(2 * f.x(m) - (f.x(a) + f.x(b)) / 2, 2 * f.y(m) - (f.y(a) + f.y(b)) / 2, f.x(b), f.y(b))
        }
        p.close()
        return p
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val f = frame ?: return
        canvas.drawBitmap(bmp, imgMatrix, bitmapPaint)

        val viewPath = framePath(f).apply { transform(imgMatrix) }
        val dim = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(offX, offY, offX + bmp.width * scale, offY + bmp.height * scale, Path.Direction.CW)
            addPath(viewPath)
        }
        canvas.drawPath(dim, dimPaint)
        canvas.drawPath(viewPath, framePaint)

        for (i in 0 until 8) {
            val x = offX + f.x(i) * scale
            val y = offY + f.y(i) * scale
            val r = dp(if (i % 2 == 0) 11f else 8f)
            if (i == active) canvas.drawCircle(x, y, dp(26f), activeFill)
            canvas.drawCircle(x, y, r, handleFill)
            canvas.drawCircle(x, y, r, handleStroke)
        }
        if (active >= 0) drawMagnifier(canvas, bmp, f)
    }

    private fun drawMagnifier(canvas: Canvas, bmp: Bitmap, f: PageFrame) {
        val hx = f.x(active); val hy = f.y(active)
        val vx = offX + hx * scale; val vy = offY + hy * scale
        val r = dp(70f)
        val margin = dp(12f)
        // lupa po przeciwnej stronie niż palec
        val cx = if (vx < width / 2f) width - r - margin else r + margin
        val cy = if (vy < height * 0.4f) height - r - margin else r + margin
        val zoom = scale * 3f
        val m = Matrix().apply {
            setScale(zoom, zoom)
            postTranslate(cx - hx * zoom, cy - hy * zoom)
        }
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bmp, m, bitmapPaint)
        canvas.drawPath(framePath(f).apply { transform(m) }, thinPaint)
        canvas.drawLine(cx - dp(14f), cy, cx + dp(14f), cy, crossPaint)
        canvas.drawLine(cx, cy - dp(14f), cx, cy + dp(14f), crossPaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, r, magBorder)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        val f = frame ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1
                var bestD = dp(44f)
                for (i in 0 until 8) {
                    val d = hypot(event.x - (offX + f.x(i) * scale), event.y - (offY + f.y(i) * scale))
                    // rogi mają pierwszeństwo przed środkami krawędzi
                    val adj = if (i % 2 == 0) d - dp(6f) else d
                    if (adj < bestD) { bestD = adj; best = i }
                }
                if (best < 0) return false
                active = best
                touchStartX = event.x; touchStartY = event.y
                startFrame = f.copy()
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val sf = startFrame ?: return false
                if (active < 0) return false
                val dx = (event.x - touchStartX) / scale
                val dy = (event.y - touchStartY) / scale
                val nf = sf.copy()
                val nx = (sf.x(active) + dx).coerceIn(0f, bmp.width.toFloat())
                val ny = (sf.y(active) + dy).coerceIn(0f, bmp.height.toFloat())
                nf.set(active, nx, ny)
                if (active % 2 == 0) {
                    // przesunięcie rogu zachowuje wygięcie sąsiednich krawędzi
                    val mx = (nx - sf.x(active)) / 2; val my = (ny - sf.y(active)) / 2
                    for (mid in intArrayOf((active + 1) % 8, (active + 7) % 8)) {
                        nf.set(mid, (sf.x(mid) + mx).coerceIn(0f, bmp.width.toFloat()), (sf.y(mid) + my).coerceIn(0f, bmp.height.toFloat()))
                    }
                }
                frame = nf
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                startFrame = null
                invalidate()
                return true
            }
        }
        return false
    }
}
