package com.modul.gymai.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
        private const val POINT_RADIUS = 6.5f
        private const val LINE_WIDTH = 5f
        private const val BONE_ALPHA = 168
        private const val KEYPOINT_ALPHA = 152
        private const val LOW_CONFIDENCE_ALPHA = 96
        private const val INNER_POINT_ALPHA = 170
        private const val INNER_POINT_SCALE = 0.34f
        private const val MIN_CONFIDENCE_TO_STABILIZE = 0.3f
        private const val MICRO_JITTER_THRESHOLD = 0.0042f
        private const val SMALL_MOVEMENT_THRESHOLD = 0.019f
        private const val SNAP_MOVEMENT_THRESHOLD = 0.058f
        private const val SMALL_MOVEMENT_ALPHA = 0.26f
        private const val DEFAULT_MOVEMENT_ALPHA = 0.56f
        private const val ARM_MICRO_JITTER_THRESHOLD = 0.0054f
        private const val ARM_SMALL_MOVEMENT_THRESHOLD = 0.024f
        private const val ARM_SNAP_MOVEMENT_THRESHOLD = 0.072f
        private const val ARM_SMALL_MOVEMENT_ALPHA = 0.14f
        private const val ARM_DEFAULT_MOVEMENT_ALPHA = 0.38f
        private const val TRANSIENT_CONFIDENCE_HOLD_FRAMES = 3
        private const val POSE_KEYPOINT_COUNT = 17
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
    private var lastDisplayKeypoints: List<Keypoint>? = null
    private var lowConfidenceHoldFrames = IntArray(POSE_KEYPOINT_COUNT)
    private val armKeypoints = setOf(
        Keypoint.LEFT_SHOULDER,
        Keypoint.RIGHT_SHOULDER,
        Keypoint.LEFT_ELBOW,
        Keypoint.RIGHT_ELBOW,
        Keypoint.LEFT_WRIST,
        Keypoint.RIGHT_WRIST
    )

    private val colorCorrect = Color.parseColor("#66B38C")
    private val colorIncorrect = Color.parseColor("#D39A62")
    private val colorPoint = Color.parseColor("#D9E4EC")
    private val colorLowConf = Color.parseColor("#BFC8D0")

    /** Sets whether the camera being used is the front camera (for mirroring). */
    fun setFrontCamera(isFront: Boolean) {
        this.isFrontCamera = isFront
    }

    fun updatePose(pose: PoseResult?, correct: Boolean = true) {
        this.isCorrect = correct
        this.poseResult = pose?.copy(keypoints = stabilizeDisplayKeypoints(pose.keypoints))
        invalidate()
    }

    fun clear() {
        poseResult = null
        lastDisplayKeypoints = null
        lowConfidenceHoldFrames = IntArray(POSE_KEYPOINT_COUNT)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pose = poseResult ?: return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val drawBounds = calculatePreviewBounds(pose, w, h)

        val boneColor = if (isCorrect) colorCorrect else colorIncorrect
        bonePaint.color = boneColor

        // Draw skeleton connections
        for ((startIdx, endIdx) in Keypoint.SKELETON_CONNECTIONS) {
            val start = pose.keypoints.getOrNull(startIdx) ?: continue
            val end = pose.keypoints.getOrNull(endIdx) ?: continue

            // Threshold ditingkatkan untuk mencegah "ghosting" pada background
            if (start.confidence < 0.4f || end.confidence < 0.4f) continue

            val sx = mapX(start.x, drawBounds)
            val sy = mapY(start.y, drawBounds)
            val ex = mapX(end.x, drawBounds)
            val ey = mapY(end.y, drawBounds)

            bonePaint.alpha = BONE_ALPHA
            canvas.drawLine(sx, sy, ex, ey, bonePaint)
        }

        // Draw keypoints
        for (kp in pose.keypoints) {
            if (kp.confidence < 0.4f) continue

            keypointPaint.color = if (kp.confidence > 0.65f) colorPoint else colorLowConf
            keypointPaint.alpha = if (kp.confidence > 0.65f) KEYPOINT_ALPHA else LOW_CONFIDENCE_ALPHA

            val cx = mapX(kp.x, drawBounds)
            val cy = mapY(kp.y, drawBounds)

            // Background circle
            canvas.drawCircle(cx, cy, POINT_RADIUS, keypointPaint)

            // Keep a subtle core so keypoints stay readable on mixed backgrounds.
            keypointPaint.color = Color.WHITE
            keypointPaint.alpha = INNER_POINT_ALPHA
            canvas.drawCircle(cx, cy, POINT_RADIUS * INNER_POINT_SCALE, keypointPaint)
        }
    }

    private fun calculatePreviewBounds(pose: PoseResult, viewWidth: Float, viewHeight: Float): RectF {
        val sourceWidth = pose.sourceWidth.takeIf { it > 0f } ?: viewWidth
        val sourceHeight = pose.sourceHeight.takeIf { it > 0f } ?: viewHeight
        val sourceAspect = sourceWidth / sourceHeight
        val viewAspect = viewWidth / viewHeight

        return if (sourceAspect > viewAspect) {
            val contentHeight = viewWidth / sourceAspect
            val verticalOffset = (viewHeight - contentHeight) / 2f
            RectF(0f, verticalOffset, viewWidth, verticalOffset + contentHeight)
        } else {
            val contentWidth = viewHeight * sourceAspect
            val horizontalOffset = (viewWidth - contentWidth) / 2f
            RectF(horizontalOffset, 0f, horizontalOffset + contentWidth, viewHeight)
        }
    }

    private fun mapX(normalizedX: Float, bounds: RectF): Float {
        val x = normalizedX.coerceIn(0f, 1f)
        return if (isFrontCamera) {
            bounds.left + ((1f - x) * bounds.width())
        } else {
            bounds.left + (x * bounds.width())
        }
    }

    private fun mapY(normalizedY: Float, bounds: RectF): Float {
        return bounds.top + (normalizedY.coerceIn(0f, 1f) * bounds.height())
    }

    private fun stabilizeDisplayKeypoints(currentKeypoints: List<Keypoint>): List<Keypoint> {
        val previousKeypoints = lastDisplayKeypoints
        if (previousKeypoints == null || previousKeypoints.size != currentKeypoints.size) {
            lastDisplayKeypoints = currentKeypoints
            return currentKeypoints
        }

        val stabilized = currentKeypoints.mapIndexed { index, current ->
            val previous = previousKeypoints[index]
            val isArmKeypoint = index in armKeypoints
            val microJitterThreshold = if (isArmKeypoint) ARM_MICRO_JITTER_THRESHOLD else MICRO_JITTER_THRESHOLD
            val smallMovementThreshold = if (isArmKeypoint) ARM_SMALL_MOVEMENT_THRESHOLD else SMALL_MOVEMENT_THRESHOLD
            val snapMovementThreshold = if (isArmKeypoint) ARM_SNAP_MOVEMENT_THRESHOLD else SNAP_MOVEMENT_THRESHOLD

            if (current.confidence < MIN_CONFIDENCE_TO_STABILIZE) {
                if (
                    previous.confidence >= MIN_CONFIDENCE_TO_STABILIZE &&
                    lowConfidenceHoldFrames[index] < TRANSIENT_CONFIDENCE_HOLD_FRAMES
                ) {
                    lowConfidenceHoldFrames[index] += 1
                    return@mapIndexed previous.copy(confidence = maxOf(current.confidence, previous.confidence * 0.9f))
                }
                lowConfidenceHoldFrames[index] = 0
                return@mapIndexed current
            }

            lowConfidenceHoldFrames[index] = 0
            if (previous.confidence < MIN_CONFIDENCE_TO_STABILIZE) {
                return@mapIndexed current
            }

            val dx = current.x - previous.x
            val dy = current.y - previous.y
            val movement = kotlin.math.sqrt(dx * dx + dy * dy)

            when {
                movement < microJitterThreshold ->
                    previous.copy(confidence = current.confidence)
                movement > snapMovementThreshold ->
                    current
                else -> {
                    val baseAlpha = if (movement < smallMovementThreshold) {
                        if (isArmKeypoint) ARM_SMALL_MOVEMENT_ALPHA else SMALL_MOVEMENT_ALPHA
                    } else {
                        if (isArmKeypoint) ARM_DEFAULT_MOVEMENT_ALPHA else DEFAULT_MOVEMENT_ALPHA
                    }
                    val alpha = confidenceAwareAlpha(baseAlpha, current.confidence, previous.confidence)
                    Keypoint(
                        x = lerp(previous.x, current.x, alpha),
                        y = lerp(previous.y, current.y, alpha),
                        confidence = current.confidence
                    )
                }
            }
        }

        lastDisplayKeypoints = stabilized
        return stabilized
    }

    private fun lerp(start: Float, end: Float, alpha: Float): Float {
        return start + (end - start) * alpha
    }

    private fun confidenceAwareAlpha(baseAlpha: Float, currentConfidence: Float, previousConfidence: Float): Float {
        val minConfidence = minOf(currentConfidence, previousConfidence)
        val confidenceScale = when {
            minConfidence < 0.4f -> 0.62f
            minConfidence < 0.55f -> 0.78f
            minConfidence < 0.7f -> 0.9f
            else -> 1f
        }
        return (baseAlpha * confidenceScale).coerceIn(0.1f, 0.9f)
    }
}
