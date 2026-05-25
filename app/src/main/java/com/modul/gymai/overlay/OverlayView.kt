package com.modul.gymai.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
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
        private const val POINT_RADIUS = 12f
        private const val LINE_WIDTH = 10f
        private const val BONE_ALPHA = 186
        private const val KEYPOINT_ALPHA = 174
        private const val LOW_CONFIDENCE_ALPHA = 112
        private const val INNER_POINT_ALPHA = 190
        private const val INNER_POINT_SCALE = 0.38f
        private const val MIN_CONFIDENCE_TO_STABILIZE = 0.30f
        private const val MIN_CONFIDENCE_TO_DRAW = 0.40f
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.65f
        private const val MICRO_JITTER_THRESHOLD = 0.0092f
        private const val SNAP_MOVEMENT_THRESHOLD = 0.102f
        private const val HARD_SNAP_MOVEMENT_MULTIPLIER = 1.85f
        private const val BODY_MICRO_ALPHA = 0.07f
        private const val BODY_MIN_ALPHA = 0.26f
        private const val BODY_MAX_ALPHA = 0.76f
        private const val BODY_FAST_SPEED = 0.62f
        private const val ARM_MICRO_JITTER_THRESHOLD = 0.0078f
        private const val ARM_SNAP_MOVEMENT_THRESHOLD = 0.090f
        private const val ARM_MICRO_ALPHA = 0.14f
        private const val ARM_MIN_ALPHA = 0.42f
        private const val ARM_MAX_ALPHA = 0.88f
        private const val ARM_FAST_SPEED = 0.78f
        private const val FRONT_BODY_MICRO_JITTER_THRESHOLD = 0.0102f
        private const val FRONT_BODY_SNAP_MOVEMENT_THRESHOLD = 0.092f
        private const val FRONT_BODY_MICRO_ALPHA = 0.12f
        private const val FRONT_BODY_MIN_ALPHA = 0.34f
        private const val FRONT_BODY_MAX_ALPHA = 0.82f
        private const val FRONT_BODY_FAST_SPEED = 0.56f
        private const val FRONT_ARM_MICRO_JITTER_THRESHOLD = 0.0092f
        private const val FRONT_ARM_SNAP_MOVEMENT_THRESHOLD = 0.082f
        private const val FRONT_ARM_MICRO_ALPHA = 0.20f
        private const val FRONT_ARM_MIN_ALPHA = 0.50f
        private const val FRONT_ARM_MAX_ALPHA = 0.92f
        private const val FRONT_ARM_FAST_SPEED = 0.66f

        // Leg keypoints (hip, knee) — side-view exercises (e.g. biceps curl) cause ML Kit
        // to hallucinate / jitter lower-body joints because they are partially
        // occluded. Use more aggressive stabilization: larger jitter threshold,
        // lower alpha, and smaller snap threshold.
        private const val LEG_MICRO_JITTER_THRESHOLD = 0.018f
        private const val LEG_SNAP_MOVEMENT_THRESHOLD = 0.065f
        private const val LEG_MICRO_ALPHA = 0.04f
        private const val LEG_MIN_ALPHA = 0.20f
        private const val LEG_MAX_ALPHA = 0.65f
        private const val LEG_FAST_SPEED = 0.50f
        private const val FRONT_LEG_MICRO_JITTER_THRESHOLD = 0.016f
        private const val FRONT_LEG_SNAP_MOVEMENT_THRESHOLD = 0.072f
        private const val FRONT_LEG_MICRO_ALPHA = 0.06f
        private const val FRONT_LEG_MIN_ALPHA = 0.26f
        private const val FRONT_LEG_MAX_ALPHA = 0.70f
        private const val FRONT_LEG_FAST_SPEED = 0.54f

        // Ankle keypoints — paling rentan "terangkat" saat bicep curl side-view karena
        // posisi paling jauh dari tubuh dan ML Kit kesulitan mengunci posisinya.
        // Gunakan stabilisasi ultra-agresif: jitter threshold sangat besar, alpha mikro
        // sangat kecil, dan snap threshold kecil agar pergerakan liar cepat diabaikan.
        private const val ANKLE_MICRO_JITTER_THRESHOLD = 0.032f
        private const val ANKLE_SNAP_MOVEMENT_THRESHOLD = 0.048f
        private const val ANKLE_MICRO_ALPHA = 0.018f
        private const val ANKLE_MIN_ALPHA = 0.12f
        private const val ANKLE_MAX_ALPHA = 0.45f
        private const val ANKLE_FAST_SPEED = 0.38f
        private const val FRONT_ANKLE_MICRO_JITTER_THRESHOLD = 0.028f
        private const val FRONT_ANKLE_SNAP_MOVEMENT_THRESHOLD = 0.055f
        private const val FRONT_ANKLE_MICRO_ALPHA = 0.025f
        private const val FRONT_ANKLE_MIN_ALPHA = 0.16f
        private const val FRONT_ANKLE_MAX_ALPHA = 0.52f
        private const val FRONT_ANKLE_FAST_SPEED = 0.42f

        private const val TRANSIENT_CONFIDENCE_HOLD_FRAMES = 4
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
    private var lastDisplayUpdateNs: Long = 0L
    private var lowConfidenceHoldFrames = IntArray(POSE_KEYPOINT_COUNT)
    private val armKeypoints = setOf(
        Keypoint.LEFT_SHOULDER,
        Keypoint.RIGHT_SHOULDER,
        Keypoint.LEFT_ELBOW,
        Keypoint.RIGHT_ELBOW,
        Keypoint.LEFT_WRIST,
        Keypoint.RIGHT_WRIST
    )

    private val legKeypoints = setOf(
        Keypoint.LEFT_HIP,
        Keypoint.RIGHT_HIP,
        Keypoint.LEFT_KNEE,
        Keypoint.RIGHT_KNEE
    )

    // Ankle dipisahkan dari legKeypoints karena butuh stabilisasi ultra-agresif
    // — paling sering "terangkat" saat bicep curl side-view.
    private val ankleKeypoints = setOf(
        Keypoint.LEFT_ANKLE,
        Keypoint.RIGHT_ANKLE
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
        lastDisplayUpdateNs = 0L
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
            if (start.confidence < MIN_CONFIDENCE_TO_DRAW || end.confidence < MIN_CONFIDENCE_TO_DRAW) continue

            val sx = mapX(start.x, drawBounds)
            val sy = mapY(start.y, drawBounds)
            val ex = mapX(end.x, drawBounds)
            val ey = mapY(end.y, drawBounds)

            bonePaint.alpha = BONE_ALPHA
            canvas.drawLine(sx, sy, ex, ey, bonePaint)
        }

        // Draw keypoints
        for (kp in pose.keypoints) {
            if (kp.confidence < MIN_CONFIDENCE_TO_DRAW) continue

            keypointPaint.color = if (kp.confidence > HIGH_CONFIDENCE_THRESHOLD) colorPoint else colorLowConf
            keypointPaint.alpha = if (kp.confidence > HIGH_CONFIDENCE_THRESHOLD) KEYPOINT_ALPHA else LOW_CONFIDENCE_ALPHA

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
            val contentWidth = viewHeight * sourceAspect
            val horizontalOffset = (viewWidth - contentWidth) / 2f
            RectF(horizontalOffset, 0f, horizontalOffset + contentWidth, viewHeight)
        } else {
            val contentHeight = viewWidth / sourceAspect
            val verticalOffset = (viewHeight - contentHeight) / 2f
            RectF(0f, verticalOffset, viewWidth, verticalOffset + contentHeight)
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
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val elapsedSeconds = if (lastDisplayUpdateNs == 0L) {
            0.05f
        } else {
            ((nowNs - lastDisplayUpdateNs) / 1_000_000_000f).coerceIn(0.016f, 0.12f)
        }
        lastDisplayUpdateNs = nowNs

        val previousKeypoints = lastDisplayKeypoints
        if (previousKeypoints == null || previousKeypoints.size != currentKeypoints.size) {
            lastDisplayKeypoints = currentKeypoints
            return currentKeypoints
        }

        val stabilized = currentKeypoints.mapIndexed { index, current ->
            val previous = previousKeypoints[index]
            val isArmKeypoint = index in armKeypoints
            val isAnkleKeypoint = index in ankleKeypoints
            val isLegKeypoint = index in legKeypoints
            val microJitterThreshold = microJitterThreshold(isArmKeypoint, isLegKeypoint, isAnkleKeypoint)
            val snapMovementThreshold = snapMovementThreshold(isArmKeypoint, isLegKeypoint, isAnkleKeypoint)

            if (current.confidence < MIN_CONFIDENCE_TO_DRAW) {
                if (
                    previous.confidence >= MIN_CONFIDENCE_TO_DRAW &&
                    lowConfidenceHoldFrames[index] < TRANSIENT_CONFIDENCE_HOLD_FRAMES
                ) {
                    lowConfidenceHoldFrames[index] += 1
                    return@mapIndexed previous.copy(confidence = maxOf(current.confidence, previous.confidence * 0.9f))
                }
            }

            if (current.confidence < MIN_CONFIDENCE_TO_STABILIZE) {
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
            val speed = movement / elapsedSeconds

            when {
                movement < microJitterThreshold -> {
                    val alpha = microAlpha(isArmKeypoint, isLegKeypoint, isAnkleKeypoint)
                    Keypoint(
                        x = lerp(previous.x, current.x, alpha),
                        y = lerp(previous.y, current.y, alpha),
                        confidence = current.confidence
                    )
                }
                movement > snapMovementThreshold -> {
                    if (movement > snapMovementThreshold * HARD_SNAP_MOVEMENT_MULTIPLIER) {
                        current
                    } else {
                        val alpha = snapAlpha(isArmKeypoint, isLegKeypoint, isAnkleKeypoint)
                        Keypoint(
                            x = lerp(previous.x, current.x, alpha),
                            y = lerp(previous.y, current.y, alpha),
                            confidence = current.confidence
                        )
                    }
                }
                else -> {
                    val alpha = adaptiveAlpha(
                        isArmKeypoint = isArmKeypoint,
                        isLegKeypoint = isLegKeypoint,
                        isAnkleKeypoint = isAnkleKeypoint,
                        movement = movement,
                        speed = speed,
                        snapMovementThreshold = snapMovementThreshold,
                        currentConfidence = current.confidence,
                        previousConfidence = previous.confidence
                    )
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

    private fun microJitterThreshold(isArmKeypoint: Boolean, isLegKeypoint: Boolean, isAnkleKeypoint: Boolean): Float {
        return when {
            isFrontCamera && isArmKeypoint -> FRONT_ARM_MICRO_JITTER_THRESHOLD
            isFrontCamera && isAnkleKeypoint -> FRONT_ANKLE_MICRO_JITTER_THRESHOLD
            isFrontCamera && isLegKeypoint -> FRONT_LEG_MICRO_JITTER_THRESHOLD
            isFrontCamera -> FRONT_BODY_MICRO_JITTER_THRESHOLD
            isArmKeypoint -> ARM_MICRO_JITTER_THRESHOLD
            isAnkleKeypoint -> ANKLE_MICRO_JITTER_THRESHOLD
            isLegKeypoint -> LEG_MICRO_JITTER_THRESHOLD
            else -> MICRO_JITTER_THRESHOLD
        }
    }

    private fun snapMovementThreshold(isArmKeypoint: Boolean, isLegKeypoint: Boolean, isAnkleKeypoint: Boolean): Float {
        return when {
            isFrontCamera && isArmKeypoint -> FRONT_ARM_SNAP_MOVEMENT_THRESHOLD
            isFrontCamera && isAnkleKeypoint -> FRONT_ANKLE_SNAP_MOVEMENT_THRESHOLD
            isFrontCamera && isLegKeypoint -> FRONT_LEG_SNAP_MOVEMENT_THRESHOLD
            isFrontCamera -> FRONT_BODY_SNAP_MOVEMENT_THRESHOLD
            isArmKeypoint -> ARM_SNAP_MOVEMENT_THRESHOLD
            isAnkleKeypoint -> ANKLE_SNAP_MOVEMENT_THRESHOLD
            isLegKeypoint -> LEG_SNAP_MOVEMENT_THRESHOLD
            else -> SNAP_MOVEMENT_THRESHOLD
        }
    }

    private fun microAlpha(isArmKeypoint: Boolean, isLegKeypoint: Boolean, isAnkleKeypoint: Boolean): Float {
        return when {
            isFrontCamera && isArmKeypoint -> FRONT_ARM_MICRO_ALPHA
            isFrontCamera && isAnkleKeypoint -> FRONT_ANKLE_MICRO_ALPHA
            isFrontCamera && isLegKeypoint -> FRONT_LEG_MICRO_ALPHA
            isFrontCamera -> FRONT_BODY_MICRO_ALPHA
            isArmKeypoint -> ARM_MICRO_ALPHA
            isAnkleKeypoint -> ANKLE_MICRO_ALPHA
            isLegKeypoint -> LEG_MICRO_ALPHA
            else -> BODY_MICRO_ALPHA
        }
    }

    private fun snapAlpha(isArmKeypoint: Boolean, isLegKeypoint: Boolean, isAnkleKeypoint: Boolean): Float {
        return when {
            isFrontCamera && isArmKeypoint -> 0.74f
            isFrontCamera && isAnkleKeypoint -> 0.42f
            isFrontCamera && isLegKeypoint -> 0.60f
            isFrontCamera -> 0.68f
            isArmKeypoint -> 0.72f
            isAnkleKeypoint -> 0.38f
            isLegKeypoint -> 0.58f
            else -> 0.64f
        }
    }

    private fun adaptiveAlpha(
        isArmKeypoint: Boolean,
        isLegKeypoint: Boolean,
        isAnkleKeypoint: Boolean,
        movement: Float,
        speed: Float,
        snapMovementThreshold: Float,
        currentConfidence: Float,
        previousConfidence: Float
    ): Float {
        val minAlpha = when {
            isFrontCamera && isArmKeypoint -> FRONT_ARM_MIN_ALPHA
            isFrontCamera && isAnkleKeypoint -> FRONT_ANKLE_MIN_ALPHA
            isFrontCamera && isLegKeypoint -> FRONT_LEG_MIN_ALPHA
            isFrontCamera -> FRONT_BODY_MIN_ALPHA
            isArmKeypoint -> ARM_MIN_ALPHA
            isAnkleKeypoint -> ANKLE_MIN_ALPHA
            isLegKeypoint -> LEG_MIN_ALPHA
            else -> BODY_MIN_ALPHA
        }
        val maxAlpha = when {
            isFrontCamera && isArmKeypoint -> FRONT_ARM_MAX_ALPHA
            isFrontCamera && isAnkleKeypoint -> FRONT_ANKLE_MAX_ALPHA
            isFrontCamera && isLegKeypoint -> FRONT_LEG_MAX_ALPHA
            isFrontCamera -> FRONT_BODY_MAX_ALPHA
            isArmKeypoint -> ARM_MAX_ALPHA
            isAnkleKeypoint -> ANKLE_MAX_ALPHA
            isLegKeypoint -> LEG_MAX_ALPHA
            else -> BODY_MAX_ALPHA
        }
        val fastSpeed = when {
            isFrontCamera && isArmKeypoint -> FRONT_ARM_FAST_SPEED
            isFrontCamera && isAnkleKeypoint -> FRONT_ANKLE_FAST_SPEED
            isFrontCamera && isLegKeypoint -> FRONT_LEG_FAST_SPEED
            isFrontCamera -> FRONT_BODY_FAST_SPEED
            isArmKeypoint -> ARM_FAST_SPEED
            isAnkleKeypoint -> ANKLE_FAST_SPEED
            isLegKeypoint -> LEG_FAST_SPEED
            else -> BODY_FAST_SPEED
        }
        val speedFactor = (speed / fastSpeed).coerceIn(0f, 1f)
        val movementFactor = (movement / snapMovementThreshold).coerceIn(0f, 1f)
        val motionFactor = maxOf(speedFactor, movementFactor)
        val motionAlpha = minAlpha + ((maxAlpha - minAlpha) * motionFactor)

        val minConfidence = minOf(currentConfidence, previousConfidence)
        val confidenceScale = when {
            minConfidence < 0.4f -> 0.76f
            minConfidence < 0.55f -> 0.86f
            minConfidence < 0.7f -> 0.94f
            else -> 1f
        }
        return (motionAlpha * confidenceScale).coerceIn(minAlpha * 0.75f, maxAlpha)
    }
}
