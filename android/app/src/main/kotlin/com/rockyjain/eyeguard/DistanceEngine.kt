package com.rockyjain.eyeguard

import android.util.Log
import kotlin.math.max

class DistanceEngine {

    companion object {

        private const val TAG =
            "EyeGuardDistance"

        // --------------------------------------------------
        // Calibration
        // --------------------------------------------------

        const val CALIBRATION_DISTANCE_CM =
            40.0

        // --------------------------------------------------
        // Hysteresis thresholds
        // --------------------------------------------------

        const val SAFE_DISTANCE_CM =
            40.0

        const val WARNING_DISTANCE_CM =
            35.0

        const val TOO_CLOSE_DISTANCE_CM =
            30.0

        // --------------------------------------------------
        // Faster smoothing
        // --------------------------------------------------

        /*
         * Previous implementation:
         *
         * 75% previous
         * 25% new
         *
         * This was too slow for the warning use case.
         *
         * Now:
         *
         * 50% previous
         * 50% new
         */

        private const val SMOOTHING_FACTOR =
            0.50
    }

    private var referenceFaceWidth:
        Double? = null

    private var smoothedDistance:
        Double? = null

    // --------------------------------------------------
    // Calibration
    // --------------------------------------------------

    fun setReferenceFaceWidth(
        width: Double
    ) {

        if (width <= 0) {

            Log.w(
                TAG,
                "Invalid calibration width: $width"
            )

            return
        }

        referenceFaceWidth =
            width

        /*
         * Start smoothing from calibration distance.
         */

        smoothedDistance =
            CALIBRATION_DISTANCE_CM

        Log.d(
            TAG,
            "Calibration set: " +
                "referenceWidth=$width px " +
                "at ${CALIBRATION_DISTANCE_CM} cm"
        )
    }

    fun getReferenceFaceWidth():
        Double? {

        return referenceFaceWidth
    }

    fun isCalibrated():
        Boolean {

        return referenceFaceWidth != null &&
            referenceFaceWidth!! > 0
    }

    // --------------------------------------------------
    // Distance calculation
    // --------------------------------------------------

    fun calculateDistance(
        currentFaceWidth: Double
    ): Double? {

        val referenceWidth =
            referenceFaceWidth

        if (
            referenceWidth == null ||
            referenceWidth <= 0 ||
            currentFaceWidth <= 0
        ) {

            return null
        }

        /*
         * Distance is inversely proportional
         * to detected face width.
         */

        val rawDistance =
            CALIBRATION_DISTANCE_CM *
                (
                    referenceWidth /
                        currentFaceWidth
                )

        val distance =
            smoothDistance(
                rawDistance
            )

        Log.d(
            TAG,
            "Distance: " +
                "reference=$referenceWidth px, " +
                "current=$currentFaceWidth px, " +
                "raw=${"%.1f".format(rawDistance)} cm, " +
                "smooth=${"%.1f".format(distance)} cm"
        )

        return distance
    }

    // --------------------------------------------------
    // Smoothing
    // --------------------------------------------------

    private fun smoothDistance(
        newDistance: Double
    ): Double {

        val previous =
            smoothedDistance

        if (previous == null) {

            smoothedDistance =
                newDistance

            return newDistance
        }

        /*
         * Faster response:
         *
         * 50% previous
         * 50% current
         */

        val result =
            (
                previous *
                    (1.0 - SMOOTHING_FACTOR)
            ) +
            (
                newDistance *
                    SMOOTHING_FACTOR
            )

        smoothedDistance =
            result

        return result
    }

    // --------------------------------------------------
    // Status
    // --------------------------------------------------

    fun getStatus(
        distance: Double?
    ): DistanceStatus {

        if (distance == null) {
            return DistanceStatus.UNKNOWN
        }

        return when {

            distance <
                TOO_CLOSE_DISTANCE_CM -> {

                DistanceStatus.TOO_CLOSE
            }

            distance <
                WARNING_DISTANCE_CM -> {

                DistanceStatus.WARNING
            }

            else -> {

                DistanceStatus.SAFE
            }
        }
    }

    // --------------------------------------------------
    // Reset smoothing
    // --------------------------------------------------

    fun resetSmoothing() {

        smoothedDistance = null

        Log.d(
            TAG,
            "Distance smoothing reset"
        )
    }
}

// ------------------------------------------------------
// Distance status
// ------------------------------------------------------

enum class DistanceStatus {

    UNKNOWN,

    SAFE,

    WARNING,

    TOO_CLOSE
}