package com.rockyjain.eyeguard

import android.util.Log

class DistanceWarningController {

    companion object {

        private const val TAG =
            "EyeGuardWarning"

        // --------------------------------------------------
        // Confirmation time
        // --------------------------------------------------

        private const val WARNING_DELAY_MS =
            1_000L

        // --------------------------------------------------
        // Tolerance for temporary ML Kit misses
        // --------------------------------------------------

        /*
         * A single missed face frame should NOT reset
         * the 1-second warning timer.
         *
         * ML Kit can occasionally miss a frame because of:
         *
         * - motion
         * - lighting
         * - blur
         * - camera frame timing
         *
         * We tolerate up to 500 ms without a face.
         */

        private const val FACE_LOST_GRACE_MS =
            500L
    }

    private var tooCloseSince:
        Long? = null

    private var lastFaceDetectedAt:
        Long? = null

    private var warningVisible =
        false

    private var latestDistance:
        Double? = null

    // --------------------------------------------------
    // Last KNOWN distance (never cleared on face loss)
    //
    // A very close face often leaves the detection
    // frame entirely. This lets us detect that the
    // face disappeared while the user was too close.
    // --------------------------------------------------

    private var lastKnownDistance:
        Double? = null

    // --------------------------------------------------
    // Distance update
    // --------------------------------------------------

    @Synchronized
    fun update(
        distance: Double?
    ) {

        val now =
            System.currentTimeMillis()

        // ----------------------------------------------
        // Remember the last real reading.
        // ----------------------------------------------

        if (
            distance != null
        ) {

            lastKnownDistance =
                distance
        }

        latestDistance =
            distance

        // ------------------------------------------------
        // FACE DETECTED
        // ------------------------------------------------

        if (distance != null) {

            lastFaceDetectedAt =
                now

            // --------------------------------------------
            // Warning already active
            // --------------------------------------------

            if (warningVisible) {

                /*
                 * Hysteresis:
                 *
                 * Warning remains visible until
                 * distance reaches 40 cm.
                 */

                if (
                    distance >=
                    DistanceEngine.SAFE_DISTANCE_CM
                ) {

                    warningVisible =
                        false

                    tooCloseSince =
                        null

                    Log.d(
                        TAG,
                        "Distance safe again. " +
                            "Warning cleared. " +
                            "distance=$distance cm"
                    )
                }

                return
            }

            // --------------------------------------------
            // Below warning threshold
            // --------------------------------------------

            if (
                distance <
                DistanceEngine.WARNING_DISTANCE_CM
            ) {

                if (tooCloseSince == null) {

                    tooCloseSince =
                        now

                    Log.d(
                        TAG,
                        "Too close detected. " +
                            "Starting 1 second timer. " +
                            "distance=$distance cm"
                    )

                    return
                }

                val elapsed =
                    now -
                        tooCloseSince!!

                Log.d(
                    TAG,
                    "Too close timer: " +
                        "${elapsed}ms / " +
                        "${WARNING_DELAY_MS}ms"
                )

                if (
                    elapsed >=
                    WARNING_DELAY_MS
                ) {

                    warningVisible =
                        true

                    Log.d(
                        TAG,
                        "TOO CLOSE confirmed. " +
                            "Warning state ACTIVE. " +
                            "distance=$distance cm"
                    )
                }

                return
            }

            // --------------------------------------------
            // Distance safe before confirmation
            // --------------------------------------------

            /*
             * The child moved away before the 2 seconds
             * completed.
             */

            tooCloseSince =
                null

            return
        }

        // ------------------------------------------------
        // NO FACE DETECTED
        // ------------------------------------------------

        /*
         * IMPORTANT:
         *
         * Do NOT immediately reset the warning timer.
         */

        val lastSeen =
            lastFaceDetectedAt

        if (lastSeen == null) {

            return
        }

        val faceLostDuration =
            now - lastSeen

        Log.d(
            TAG,
            "Face temporarily lost: " +
                "${faceLostDuration}ms"
        )

        // ------------------------------------------------
        // Face lost while TOO CLOSE
        //
        // A very close face often leaves the
        // detection frame entirely. Losing
        // tracking must never reward leaning
        // in: keep the countdown alive and let
        // it confirm the warning even without
        // new face detections.
        // ------------------------------------------------

        val lastKnown =
            lastKnownDistance

        val wasTooClose =
            lastKnown != null &&
                lastKnown <
                    DistanceEngine.WARNING_DISTANCE_CM

        if (
            wasTooClose &&
            !warningVisible
        ) {

            if (
                tooCloseSince == null
            ) {

                tooCloseSince =
                    lastSeen
            }

            val elapsed =
                now - tooCloseSince!!

            if (
                elapsed >=
                WARNING_DELAY_MS
            ) {

                warningVisible =
                    true

                Log.d(
                    TAG,
                    "TOO CLOSE confirmed while " +
                        "face undetectable. " +
                        "Warning state ACTIVE."
                )
            }

            Log.d(
                TAG,
                "Face lost while too close. " +
                    "Countdown preserved."
            )

            return
        }

        // ------------------------------------------------
        // Short ML Kit miss
        // ------------------------------------------------

        if (
            faceLostDuration <=
            FACE_LOST_GRACE_MS
        ) {

            /*
             * Keep the current timer.
             */

            return
        }

        // ------------------------------------------------
        // Face genuinely lost
        // ------------------------------------------------

        if (!warningVisible) {

            tooCloseSince =
                null
        }

        lastFaceDetectedAt =
            null
    }

    // --------------------------------------------------
    // Warning state
    // --------------------------------------------------

    @Synchronized
    fun isWarningVisible():
        Boolean {

        return warningVisible
    }

    // --------------------------------------------------
    // Latest distance
    // --------------------------------------------------

    @Synchronized
    fun getLatestDistance():
        Double? {

        return latestDistance
    }

    // --------------------------------------------------
    // Last known distance
    // --------------------------------------------------

    @Synchronized
    fun getLastKnownDistance():
        Double? {

        return lastKnownDistance
    }

    // --------------------------------------------------
    // Reset
    // --------------------------------------------------

    @Synchronized
    fun reset() {

        tooCloseSince =
            null

        lastFaceDetectedAt =
            null

        warningVisible =
            false

        latestDistance =
            null

        lastKnownDistance =
            null

        Log.d(
            TAG,
            "Warning controller reset"
        )
    }

    // --------------------------------------------------
    // Cleanup
    // --------------------------------------------------

    fun close() {

        reset()
    }
}