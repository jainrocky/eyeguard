package com.rockyjain.eyeguard

import android.media.Image
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

class CameraAnalyzer(
    private val sensorOrientation: Int,
    private val onFaceResult: (Int, Float?, Float?) -> Unit
) {

    companion object {
        private const val TAG = "EyeGuardCameraAnalyzer"
    }

    private val detector: FaceDetector =
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(
                    FaceDetectorOptions.PERFORMANCE_MODE_FAST
                )
                .enableTracking()
                .build()
        )

@Volatile
private var isProcessing = false


    fun analyze(image: Image) {

        Log.d(
            TAG,
            "Native frame received: " +
                "${image.width}x${image.height}"
        )

        if (isProcessing) {
            image.close()
            return
        }

        isProcessing = true

        val rotation =
            rotationDegrees(sensorOrientation)

        val inputImage =
            InputImage.fromMediaImage(
                image,
                rotation
            )

        detector
            .process(inputImage)
            .addOnSuccessListener { faces ->

                if (faces.isEmpty()) {

                    Log.d(
                        TAG,
                        "No face detected"
                    )

                    onFaceResult(
                        0,
                        null,
                        null
                    )

                    return@addOnSuccessListener
                }

                val largestFace =
                    faces.maxByOrNull {
                        it.boundingBox.width() *
                            it.boundingBox.height()
                    }

                if (largestFace != null) {

                    val width =
                        largestFace.boundingBox.width()
                            .toFloat()

                    val height =
                        largestFace.boundingBox.height()
                            .toFloat()

                    Log.d(
                        TAG,
                        "Faces=${faces.size}, " +
                            "width=$width, " +
                            "height=$height"
                    )

                    onFaceResult(
                        faces.size,
                        width,
                        height
                    )
                }
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "Face detection failed",
                    error
                )
            }
            .addOnCompleteListener {

                image.close()

                isProcessing = false
            }
    }

    private fun rotationDegrees(
        sensorOrientation: Int
    ): Int {

        return when (sensorOrientation) {
            0 -> 0
            90 -> 90
            180 -> 180
            270 -> 270
            else -> 0
        }
    }

    fun close() {
        detector.close()
    }
}