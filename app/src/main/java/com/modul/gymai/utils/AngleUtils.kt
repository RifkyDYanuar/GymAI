package com.modul.gymai.utils

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Geometry utility functions for biomechanical angle calculations.
 */
object AngleUtils {

    /**
     * Calculate the angle (in degrees) at point B, formed by A-B-C.
     */
    fun angleBetween(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Float {
        val v1x = ax - bx; val v1y = ay - by
        val v2x = cx - bx; val v2y = cy - by

        val dot = v1x * v2x + v1y * v2y
        val mag = sqrt((v1x * v1x + v1y * v1y).toDouble()) *
                sqrt((v2x * v2x + v2y * v2y).toDouble())

        if (mag == 0.0) return 0f
        return Math.toDegrees(Math.acos((dot / mag).coerceIn(-1.0, 1.0))).toFloat()
    }

    /**
     * Calculate vertical angle of a vector from (x1,y1) to (x2,y2).
     * 0° = pointing up, 90° = horizontal.
     */
    fun verticalAngle(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return Math.toDegrees(atan2(abs(dx).toDouble(), abs(dy).toDouble())).toFloat()
    }

    /**
     * Euclidean distance between two normalized points.
     */
    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}
