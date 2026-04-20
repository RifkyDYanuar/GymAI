package com.modul.gymai.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.modul.gymai.pose.Keypoint
import com.modul.gymai.pose.PoseResult

/**
 * Custom view that draws the skeleton overlay on top of the camera preview.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val POINT_RADIUS = 8f
        private const val LINE_WIDTH = 5f
    }

    private val keypointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH
        strokeCap = Paint.Cap.ROUND
    }

    private var poseResult: PoseResult? = null
    private var isCorrect: Boolean = false
    private var isFrontCamera: Boolean = false

    // Colors (High Visibility)
    // Colors (High Visibility)
    private val colorCorrect = Color.parseColor("#00FF00")   // Neon Green
    private val colorIncorrect = Color.parseColor("#FFFF00") // Neon Yellow
    private val colorPoint = Color.parseColor("#FF00FF")     // Magenta/Pink (High Contrast)
    private val colorLowConf = Color.parseColor("#80FFFFFF") // Semi-transparent white

    /** Sets whether the camera being used is the front camera (for mirroring). */
    fun setFrontCamera(isFront: Boolean) {
        this.isFrontCamera = isFront
    }

    fun updatePose(pose: PoseResult?, correct: Boolean = true) {
        this.poseResult = pose
        this.isCorrect = correct
        invalidate()
    }

    fun clear() {
        poseResult = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pose = poseResult ?: return

        val w = width.toFloat()
        val h = height.toFloat()

        val boneColor = if (isCorrect) colorCorrect else colorIncorrect
        bonePaint.color = boneColor

        // Draw skeleton connections
        for ((startIdx, endIdx) in Keypoint.SKELETON_CONNECTIONS) {
            val start = pose.keypoints.getOrNull(startIdx) ?: continue
            val end = pose.keypoints.getOrNull(endIdx) ?: continue

            // Threshold ditingkatkan untuk mencegah "ghosting" pada background
            if (start.confidence < 0.4f || end.confidence < 0.4f) continue

            // Handle mirroring for front camera (flip horizontal relative to view width)
            val sx = if (isFrontCamera) (1f - start.x) * w else start.x * w
            val sy = start.y * h
            val ex = if (isFrontCamera) (1f - end.x) * w else end.x * w
            val ey = end.y * h

            bonePaint.alpha = 255
            canvas.drawLine(sx, sy, ex, ey, bonePaint)
        }

        // Draw keypoints
        for ((index, kp) in pose.keypoints.withIndex()) {
            if (kp.confidence < 0.4f) continue

            keypointPaint.color = if (kp.confidence > 0.1f) colorPoint else colorLowConf
            keypointPaint.alpha = 255

            val cx = if (isFrontCamera) (1f - kp.x) * w else kp.x * w
            val cy = kp.y * h

            // Background circle
            canvas.drawCircle(cx, cy, POINT_RADIUS, keypointPaint)

            // Inner white dot for precision look
            keypointPaint.color = Color.WHITE
            keypointPaint.alpha = 255
            canvas.drawCircle(cx, cy, POINT_RADIUS * 0.4f, keypointPaint)
        }
    }
}
