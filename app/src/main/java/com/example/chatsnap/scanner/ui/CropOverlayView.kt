package com.example.chatsnap.scanner.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 4 corners: 0 = Top-Left, 1 = Top-Right, 2 = Bottom-Right, 3 = Bottom-Left
    val points = Array(4) { PointF() }
    
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#005DFF") // Accent Blue
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000") // Semi-transparent black
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#005DFF")
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val handleRadius = 40f
    private val touchThreshold = 100f
    private var activePointIndex = -1

    init {
        // Enable software rendering if needed for EVEN_ODD path filling, although modern hardware layers support it.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * Set crop box corners. If list is empty, default to inset rectangle.
     */
    fun setCropPoints(newPoints: List<PointF>) {
        if (newPoints.size == 4) {
            for (i in 0..3) {
                points[i].set(newPoints[i])
            }
        } else {
            resetToDefault()
        }
        invalidate()
    }

    fun getCropPoints(): List<PointF> {
        return points.toList()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (points.all { it.x == 0f && it.y == 0f }) {
            resetToDefault()
        }
    }

    private fun resetToDefault() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val insetX = w * 0.15f
        val insetY = h * 0.15f

        points[0].set(insetX, insetY) // Top-Left
        points[1].set(w - insetX, insetY) // Top-Right
        points[2].set(w - insetX, h - insetY) // Bottom-Right
        points[3].set(insetX, h - insetY) // Bottom-Left
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // 1. Draw dim overlay mask with transparent crop hole (EVEN_ODD fill)
        val overlayPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            // Add outer screen bounds
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            // Add inner quadrilateral
            moveTo(points[0].x, points[0].y)
            lineTo(points[1].x, points[1].y)
            lineTo(points[2].x, points[2].y)
            lineTo(points[3].x, points[3].y)
            close()
        }
        canvas.drawPath(overlayPath, maskPaint)

        // 2. Draw connecting borders
        canvas.drawLine(points[0].x, points[0].y, points[1].x, points[1].y, linePaint)
        canvas.drawLine(points[1].x, points[1].y, points[2].x, points[2].y, linePaint)
        canvas.drawLine(points[2].x, points[2].y, points[3].x, points[3].y, linePaint)
        canvas.drawLine(points[3].x, points[3].y, points[0].x, points[0].y, linePaint)

        // 3. Draw draggable corner handles
        for (point in points) {
            canvas.drawCircle(point.x, point.y, handleRadius, handlePaint)
            canvas.drawCircle(point.x, point.y, handleRadius, handleStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activePointIndex = getClosestPointIndex(x, y)
                return activePointIndex != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointIndex != -1) {
                    // Update position bounded by screen size
                    val boundedX = x.coerceIn(0f, width.toFloat())
                    val boundedY = y.coerceIn(0f, height.toFloat())
                    
                    // Simple quadrilateral layout constraint checks:
                    // Avoid crossing top/bottom or left/right orderings.
                    when (activePointIndex) {
                        0 -> { // Top-Left
                            if (boundedX < points[1].x - 40f && boundedY < points[3].y - 40f) {
                                points[0].set(boundedX, boundedY)
                            }
                        }
                        1 -> { // Top-Right
                            if (boundedX > points[0].x + 40f && boundedY < points[2].y - 40f) {
                                points[1].set(boundedX, boundedY)
                            }
                        }
                        2 -> { // Bottom-Right
                            if (boundedX > points[3].x + 40f && boundedY > points[1].y + 40f) {
                                points[2].set(boundedX, boundedY)
                            }
                        }
                        3 -> { // Bottom-Left
                            if (boundedX < points[2].x - 40f && boundedY > points[0].y + 40f) {
                                points[3].set(boundedX, boundedY)
                            }
                        }
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointIndex = -1
            }
        }
        return true
    }

    private fun getClosestPointIndex(touchX: Float, touchY: Float): Int {
        var minDistance = Float.MAX_VALUE
        var index = -1
        for (i in 0..3) {
            val dist = sqrt((points[i].x - touchX) * (points[i].x - touchX) + (points[i].y - touchY) * (points[i].y - touchY))
            if (dist < touchThreshold && dist < minDistance) {
                minDistance = dist
                index = i
            }
        }
        return index
    }
}
