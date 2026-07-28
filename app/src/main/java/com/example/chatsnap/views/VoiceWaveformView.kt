package com.example.chatsnap.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/**
 * WhatsApp-style Voice Recording Frequency Waveform Visualizer View.
 * Displays dynamic animated vertical frequency bars that react to live microphone input.
 */
class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00A884") // WhatsApp signature green color
    }

    private val barWidthDp = 3f
    private val barGapDp = 2.5f
    private val minBarHeightDp = 4f

    private var barWidthPx = 0f
    private var barGapPx = 0f
    private var minBarHeightPx = 0f

    private var maxBars = 35
    private var currentAmplitudes = FloatArray(maxBars) { 0.08f }
    private var targetAmplitudes = FloatArray(maxBars) { 0.08f }

    private var isRecording = false
    private val rectF = RectF()
    private val random = Random(System.currentTimeMillis())

    init {
        val density = context.resources.displayMetrics.density
        barWidthPx = barWidthDp * density
        barGapPx = barGapDp * density
        minBarHeightPx = minBarHeightDp * density
    }

    fun setWaveColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val step = barWidthPx + barGapPx
        if (step > 0) {
            maxBars = max(10, (w / step).toInt())
            if (currentAmplitudes.size != maxBars) {
                currentAmplitudes = FloatArray(maxBars) { 0.08f }
                targetAmplitudes = FloatArray(maxBars) { 0.08f }
            }
        }
    }

    /**
     * Called when live microphone amplitude is received.
     * @param normAmp normalized amplitude ratio between 0.0f and 1.0f
     */
    fun addAmplitude(normAmp: Float) {
        if (maxBars <= 0) return

        // Shift target amplitudes left by 1 position (WhatsApp scrolling wave effect)
        for (i in 0 until maxBars - 1) {
            targetAmplitudes[i] = targetAmplitudes[i + 1]
        }

        // Calculate peak amplitude with natural audio frequency spectrum variation
        val clampedAmp = normAmp.coerceIn(0.05f, 1.0f)
        // Add subtle harmonic noise so silent/low audio still has subtle live wave motion
        val jitter = if (clampedAmp > 0.1f) (random.nextFloat() * 0.2f - 0.1f) else (random.nextFloat() * 0.04f - 0.02f)
        val newTarget = (clampedAmp + jitter).coerceIn(0.06f, 1.0f)

        targetAmplitudes[maxBars - 1] = newTarget

        if (!isRecording) {
            isRecording = true
        }
        postInvalidateOnAnimation()
    }

    fun startRecordingAnimation() {
        isRecording = true
        postInvalidateOnAnimation()
    }

    fun stopRecordingAnimation() {
        isRecording = false
        // Smoothly return all bars to baseline
        for (i in 0 until maxBars) {
            targetAmplitudes[i] = 0.08f
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        isRecording = false
        for (i in 0 until maxBars) {
            currentAmplitudes[i] = 0.08f
            targetAmplitudes[i] = 0.08f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0 || maxBars <= 0) return

        var needsReDraw = false
        val cornerRadius = barWidthPx / 2f
        val centerY = height / 2f
        val maxAvailableHeight = height - paddingTop - paddingBottom

        for (i in 0 until maxBars) {
            // Smooth lerp towards target amplitude
            val diff = targetAmplitudes[i] - currentAmplitudes[i]
            if (kotlin.math.abs(diff) > 0.005f) {
                currentAmplitudes[i] += diff * 0.3f
                needsReDraw = true
            } else {
                currentAmplitudes[i] = targetAmplitudes[i]
            }

            // Idle ambient wave oscillation while recording
            var amp = currentAmplitudes[i]
            if (isRecording && amp <= 0.1f) {
                val waveOffset = kotlin.math.sin((System.currentTimeMillis() / 150.0) + i * 0.4).toFloat() * 0.03f
                amp = (amp + waveOffset).coerceIn(0.05f, 1.0f)
                needsReDraw = true
            }

            val barHeight = max(minBarHeightPx, maxAvailableHeight * amp)
            val left = paddingLeft + i * (barWidthPx + barGapPx)
            val right = left + barWidthPx
            val top = centerY - (barHeight / 2f)
            val bottom = centerY + (barHeight / 2f)

            rectF.set(left, top, right, bottom)
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        }

        if (needsReDraw || isRecording) {
            postInvalidateOnAnimation()
        }
    }
}
