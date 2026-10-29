package org.odk.collect.android.alpha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

class AlphaMapView(context: Context) : View(context) {
    var rows: List<AlphaFrameRow> = emptyList()
    var overlay = AlphaMapOverlay()
    var selected: AlphaFrameRow? = null
    var location: AlphaMapPoint? = null
    var onCases: (List<AlphaFrameRow>) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var centerX = 0.5
    private var centerY = 0.5
    private var scale = 256.0
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom(detector.scaleFactor.toDouble())
            moved = true
            return true
        }
    })

    init {
        contentDescription = "Offline EA map. Drag to pan, pinch to zoom, tap markers to select cases."
        isClickable = true
    }

    fun fit() {
        val points = rows.mapNotNull { AlphaMapData.point(it) } + overlay.points() + listOfNotNull(location)
        if (points.isEmpty()) { centerX = 0.5; centerY = 0.5; scale = 256.0; invalidate(); return }
        val projected = points.map { AlphaMapProjection.project(it) }
        val minX = projected.minOf { it.first }; val maxX = projected.maxOf { it.first }
        val minY = projected.minOf { it.second }; val maxY = projected.maxOf { it.second }
        centerX = (minX + maxX) / 2; centerY = (minY + maxY) / 2
        scale = min(max(1.0, width - 64.0 * density) / max(0.00001, maxX - minX),
            max(1.0, height - 64.0 * density) / max(0.00001, maxY - minY)).coerceIn(128.0, 100_000_000.0)
        invalidate()
    }

    fun zoom(factor: Double) { scale = (scale * factor).coerceIn(128.0, 100_000_000.0); invalidate() }
    fun center(point: AlphaMapPoint) {
        val p = AlphaMapProjection.project(point); centerX = p.first; centerY = p.second; invalidate()
    }
    internal fun screen(point: AlphaMapPoint): Pair<Float, Float> {
        val p = AlphaMapProjection.project(point)
        return ((p.first - centerX) * scale + width / 2).toFloat() to
            ((p.second - centerY) * scale + height / 2).toFloat()
    }
    internal fun hit(x: Float, y: Float): List<AlphaFrameRow> = rows.filter { row ->
        AlphaMapData.point(row)?.let { point ->
            val p = screen(point); hypot(p.first - x, p.second - y) <= 24 * density
        } ?: false
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFF1F5F4.toInt())
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = 0xFFDCE4E2.toInt()
        val step = 48 * density
        var x = 0f; while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), paint); x += step }
        var y = 0f; while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += step }
        overlay.polygons.forEach { polygon ->
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            polygon.forEach { ring ->
                ring.forEachIndexed { index, point ->
                    val p = screen(point)
                    if (index == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second)
                }; path.close()
            }
            paint.style = Paint.Style.FILL; paint.color = 0x222E7D32; canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density
            paint.color = 0xFF1B5E20.toInt(); canvas.drawPath(path, paint)
        }
        paint.color = 0xFF6A1B9A.toInt(); paint.pathEffect = DashPathEffect(floatArrayOf(8 * density, 5 * density), 0f)
        overlay.bounds.forEach { b ->
            val a = screen(AlphaMapPoint(b.north, b.west)); val z = screen(AlphaMapPoint(b.south, b.east))
            canvas.drawRect(a.first, a.second, z.first, z.second, paint)
        }
        paint.pathEffect = null
        rows.forEach { row -> AlphaMapData.point(row)?.let { point ->
            val p = screen(point)
            paint.style = Paint.Style.FILL; paint.color = AlphaMapData.colors.getValue(AlphaMapData.status(row.status))
            canvas.drawCircle(p.first, p.second, 7 * density, paint)
        } }
        selected?.let { row -> AlphaMapData.point(row)?.let { point ->
            val p = screen(point)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density; paint.color = 0xFF000000.toInt()
            canvas.drawCircle(p.first, p.second, 12 * density, paint)
        } }
        location?.let { point ->
            val p = screen(point)
            paint.style = Paint.Style.FILL; paint.color = 0xFF1565C0.toInt()
            canvas.drawCircle(p.first, p.second, 9 * density, paint)
            paint.style = Paint.Style.STROKE; paint.color = 0xFFFFFFFF.toInt(); paint.strokeWidth = 2 * density
            canvas.drawCircle(p.first, p.second, 9 * density, paint)
        }
        paint.style = Paint.Style.FILL; paint.color = 0xFF263238.toInt(); paint.textSize = 12 * density
        canvas.drawText("N ↑  •  Offline field overlays", 10 * density, 20 * density, paint)
        canvas.drawText("No street or satellite basemap", 10 * density, height - 12 * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; moved = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > 8 * density) moved = true
                if (!pinch.isInProgress && event.pointerCount == 1 && moved) {
                    centerX -= (event.x - lastX) / scale; centerY -= (event.y - lastY) / scale; invalidate()
                }
            }
            MotionEvent.ACTION_UP -> { if (!moved) { performClick(); onCases(hit(event.x, event.y)) }; parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> { moved = true; parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_POINTER_DOWN -> moved = true
        }
        lastX = event.x; lastY = event.y
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
