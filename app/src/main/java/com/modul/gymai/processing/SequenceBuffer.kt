package com.modul.gymai.processing

import java.util.ArrayDeque

/**
 * Sliding window sequence buffer for CNN input.
 *
 * Maintains the last [windowSize] frames of 51-dimensional feature vectors.
 * When full, the oldest frame is discarded on each new addition.
 *
 * CNN input shape: [1, 96, 51]
 */
class SequenceBuffer(val windowSize: Int = 96) {

    private val buffer: ArrayDeque<FloatArray> = ArrayDeque(windowSize)

    /**
     * Add a new frame of features to the buffer.
     * If buffer is full, the oldest frame is removed first.
     */
    fun add(features: FloatArray) {
        if (buffer.size >= windowSize) {
            buffer.pollFirst()
        }
        buffer.addLast(features.copyOf())
    }

    /**
     * Returns true when the buffer has [windowSize] frames ready for CNN inference.
     */
    fun isFull(): Boolean = buffer.size >= windowSize

    /**
     * Returns the current fill percentage [0..100].
     */
    fun fillPercent(): Int = (buffer.size * 100) / windowSize

    /**
     * Convert buffer to a 2D array of shape [windowSize, 51] for CNN.
     * Older frames are index 0, newest frame is index [windowSize-1].
     */
    fun toArray(): Array<FloatArray> {
        val result = Array(windowSize) { FloatArray(FeatureExtractor.FEATURE_SIZE) }
        val list = buffer.toList()
        for (i in list.indices) {
            result[i] = list[i]
        }
        return result
    }

    fun clear() {
        buffer.clear()
    }

    fun size(): Int = buffer.size
}
