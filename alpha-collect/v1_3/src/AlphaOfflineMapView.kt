package org.odk.collect.android.alpha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Offline vector EA map. Uses WGS84 coordinates; does not require tiles or a network.
 * A background street/satellite basemap is deliberately NOT claimed or supplied.
 */
class AlphaOfflineMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onCaseSelected: ((AlphaFrameRow) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mapRecords = mutableListOf<AlphaFrameRow>()
    private val polygon = mutableListOf<AlphaMapPoint>()
    private var currentLocation: AlphaMapPoint? = null
    private var selectedId: String? = null

    private var referenceLatitude = 31.5
    private var worldCenterX = 0.0
    private var worldCenterY = 0.0
    private var fitScale = 1.0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(0.65f, 25f)
            invalidate()
            return true
        }
    })

    fun setData(records: List<AlphaFrameRow>, boundary: List<AlphaMapPoint>) {
        mapRecords.clear()
        mapRecords.addAll(records)
        polygon.clear()
        polygon.addAll(boundary)
        selectedId = null
        resetView()
    }

    fun setMyLocation(latitude: Double, longitude: Double) {
        currentLocation = AlphaMapPoint(latitude, longitude)
        invalidate()
    }

    fun selectRecord(id: String?) {
        selectedId = id
        invalidate()
    }

    fun resetView() {
        val points = mutableListOf<AlphaMapPoint>()
        points.addAll(polygon)
        mapRecords.forEach { row ->
            val lat = row.latitude
            val lon = row.longitude
            if (lat != null && lon != null) points.add(AlphaMapPoint(lat, lon))
        }
        referenceLatitude = points.firstOrNull()?.latitude ?: 31.5
        val cosLat = cos(Math.toRadians(referenceLatitude)).coerceAtLeast(0.01)
        if (points.isEmpty()) {
            worldCenterX = 0.0
            worldCenterY = 0.0
            fitScale = 1.0
        } else {
            val xs = points.map { it.longitude * cosLat }
            val ys = points.map { it.latitude }
            worldCenterX = (xs.minOrNull()!! + xs.maxOrNull()!!) / 2
            worldCenterY = (ys.minOrNull()!! + ys.maxOrNull()!!) / 2
            val spanX = (xs.maxOrNull()!! - xs.minOrNull()!!).coerceAtLeast(0.00002)
            val spanY = (ys.maxOrNull()!! - ys.minOrNull()!!).coerceAtLeast(0.00002)
            fitScale = min(
                (max(1, width) * 0.84) / spanX,
                (max(1, height) * 0.84) / spanY
            )
        }
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun screen(latitude: Double, longitude: Double): PointF {
        val x = longitude * cos(Math.toRadians(referenceLatitude)).coerceAtLeast(0.01)
        return PointF(
            (width / 2.0 + panX + (x - worldCenterX) * fitScale * zoom).toFloat(),
            (height / 2.0 + panY - (latitude - worldCenterY) * fitScale * zoom).toFloat()
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 || oldh == 0) resetView()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(246, 249, 252))

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.rgb(223, 230, 236)
        for (i in 1..5) {
            val x = width * i / 6f
            val y = height * i / 6f
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }

        if (polygon.size >= 3) {
            val path = Path()
            polygon.forEachIndexed { index, vertex ->
                val pos = screen(vertex.latitude, vertex.longitude)
                if (index == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
            }
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(55, 24, 117, 184)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.rgb(2, 85, 157)
            canvas.drawPath(path, paint)
        }

        mapRecords.forEach { row ->
            val lat = row.latitude ?: return@forEach
            val lon = row.longitude ?: return@forEach
            val pos = screen(lat, lon)
            if (pos.x < -15 || pos.x > width + 15 || pos.y < -15 || pos.y > height + 15) return@forEach
            paint.style = Paint.Style.FILL
            paint.color = when (row.status?.lowercase()) {
                "completed" -> Color.rgb(43, 145, 91)
                "refused" -> Color.rgb(194, 58, 60)
                "revisit" -> Color.rgb(237, 152, 28)
                "vacant", "not_found", "unavailable" -> Color.rgb(130, 140, 151)
                else -> Color.rgb(2, 112, 182)
            }
            canvas.drawCircle(pos.x, pos.y, if (row.recordId == selectedId) 9f else 5.5f, paint)
            if (row.recordId == selectedId) {
                paint.style = Paint.Style.STROKE
                paint.color = Color.rgb(30, 37, 45)
                paint.strokeWidth = 2f
                canvas.drawCircle(pos.x, pos.y, 12f, paint)
            }
        }

        currentLocation?.let { point ->
            val pos = screen(point.latitude, point.longitude)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(60, 0, 90, 230)
            canvas.drawCircle(pos.x, pos.y, 18f, paint)
            paint.color = Color.rgb(0, 90, 230)
            canvas.drawCircle(pos.x, pos.y, 8f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(pos.x, pos.y, 3f, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(68, 80, 91)
        paint.textSize = 28f
        if (mapRecords.isEmpty() && polygon.isEmpty()) {
            canvas.drawText("No mapped coordinates for assigned EA", 24f, height / 2f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (detector.isInProgress) {
                    dragging = true
                    return true
                }
                if (event.pointerCount == 1) {
                    if (hypot(event.x - downX, event.y - downY) > 12f) dragging = true
                    if (dragging) {
                        panX += event.x - lastX
                        panY += event.y - lastY
                        invalidate()
                    }
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && !detector.isInProgress) {
                    selectNearest(event.x, event.y)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun selectNearest(tapX: Float, tapY: Float) {
        var nearest: AlphaFrameRow? = null
        var bestDistance = 25.0
        mapRecords.forEach { row ->
            val lat = row.latitude ?: return@forEach
            val lon = row.longitude ?: return@forEach
            val pos = screen(lat, lon)
            val distance = hypot((pos.x - tapX).toDouble(), (pos.y - tapY).toDouble())
            if (distance < bestDistance) {
                nearest = row
                bestDistance = distance
            }
        }
        nearest?.let {
            selectedId = it.recordId
            invalidate()
            onCaseSelected?.invoke(it)
        }
    }
}
