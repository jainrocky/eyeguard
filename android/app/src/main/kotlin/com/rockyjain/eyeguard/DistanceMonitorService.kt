package com.rockyjain.eyeguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

import androidx.core.content.ContextCompat

class DistanceMonitorService : Service() {

    companion object {

        private const val TAG =
            "EyeGuardService"

        private const val CHANNEL_ID =
            "eye_guard_monitor"

        private const val NOTIFICATION_ID =
            1001

        private const val IMAGE_WIDTH =
            640

        private const val IMAGE_HEIGHT =
            480

        // --------------------------------------------------
        // Delay before retrying the camera after it
        // was taken by another app.
        // --------------------------------------------------

        private const val CAMERA_RETRY_DELAY_MS =
            3_000L
    }

    // --------------------------------------------------
    // Camera
    // --------------------------------------------------

    private lateinit var cameraManager: CameraManager

    private var cameraDevice: CameraDevice? = null

    private var captureSession:
        CameraCaptureSession? = null

    private var imageReader:
        ImageReader? = null

    private var frontCameraId:
        String? = null

    // --------------------------------------------------
    // Camera background thread
    // --------------------------------------------------

    private var cameraThread:
        HandlerThread? = null

    private var cameraHandler:
        Handler? = null

    // --------------------------------------------------
    // ML Kit analyzer
    // --------------------------------------------------

    private var cameraAnalyzer:
        CameraAnalyzer? = null

    private val distanceEngine =
    DistanceEngine()

    private val warningController =
    DistanceWarningController()

    private lateinit var overlayManager:
    OverlayManager

    // --------------------------------------------------
    // State
    // --------------------------------------------------

    private var isCameraStarting =
        false

    private var isCameraRunning =
        false

    private var isRetryScheduled =
        false

    private var isShuttingDown =
        false

    // --------------------------------------------------
    // Service lifecycle
    // --------------------------------------------------


override fun onCreate() {
    super.onCreate()

    Log.d(
        TAG,
        "Service onCreate()"
    )

    createNotificationChannel()

    overlayManager =
        OverlayManager(this)

    cameraManager =
        getSystemService(
            Context.CAMERA_SERVICE
        ) as CameraManager

    loadCalibration()
}


private fun loadCalibration() {

    val preferences =
        getSharedPreferences(
            "eye_guard_preferences",
            Context.MODE_PRIVATE
        )

    val calibrationComplete =
        preferences.getBoolean(
            "calibration_complete",
            false
        )

    val referenceWidth =
        preferences.getFloat(
            "reference_face_width",
            0f
        )

    if (
        calibrationComplete &&
        referenceWidth > 0f
    ) {

        distanceEngine.setReferenceFaceWidth(
            referenceWidth.toDouble()
        )

        Log.d(
            TAG,
            "Saved calibration loaded: " +
                "$referenceWidth px"
        )

    } else {

        Log.d(
            TAG,
            "No saved calibration found"
        )
    }
}

 
override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int
): Int {

    Log.d(
        TAG,
        "Service onStartCommand()"
    )

    // ---------------------------------------------
    // Always ensure foreground state
    // ---------------------------------------------

    val notification =
        createNotification()

    startForeground(
        NOTIFICATION_ID,
        notification
    )

    Log.d(
        TAG,
        "Foreground service started"
    )

    // ---------------------------------------------
    // Start camera only once
    // ---------------------------------------------

    if (
        !isCameraRunning &&
        !isCameraStarting
    ) {

        startCamera()

    } else {

        Log.d(
            TAG,
            "Camera already running/starting"
        )
    }

    return START_STICKY
}


    // --------------------------------------------------
    // Start camera
    // --------------------------------------------------

    private fun startCamera() {

        Log.d(
            TAG,
            "startCamera()"
        )

        // ---------------------------------------------
        // Check camera permission
        // ---------------------------------------------

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.e(
                TAG,
                "CAMERA permission NOT granted"
            )

            stopSelf()

            return
        }

        if (isCameraStarting ||
            isCameraRunning
        ) {
            Log.d(
                TAG,
                "Camera is already active"
            )

            return
        }

        isCameraStarting = true

        try {

            // -----------------------------------------
            // Find front camera
            // -----------------------------------------

            frontCameraId =
                findFrontCamera()

            Log.d(
                TAG,
                "Front camera ID = $frontCameraId"
            )

            if (frontCameraId == null) {

                Log.e(
                    TAG,
                    "Front camera not found"
                )

                isCameraStarting = false

                return
            }

            // -----------------------------------------
            // Start camera background thread
            // -----------------------------------------

            startCameraThread()

            // -----------------------------------------
            // Get camera characteristics
            // -----------------------------------------

            val cameraId =
                frontCameraId!!

            val characteristics =
                cameraManager.getCameraCharacteristics(
                    cameraId
                )

            val sensorOrientation =
                characteristics.get(
                    CameraCharacteristics.SENSOR_ORIENTATION
                ) ?: 0

            Log.d(
                TAG,
                "Sensor orientation = $sensorOrientation"
            )

            // -----------------------------------------
            // Create ML Kit analyzer
            // -----------------------------------------

           cameraAnalyzer =
    CameraAnalyzer(
        sensorOrientation
    ) { faceCount, width, height ->

        Log.d(
            TAG,
            "Face result: " +
                "faces=$faceCount " +
                "width=$width " +
                "height=$height"
        )

        // ---------------------------------------------
        // No face
        // ---------------------------------------------

        if (width == null) {

            warningController.update(
                null
            )

            /*
             * The warning can be confirmed while
             * the face is undetectable (a very
             * close face leaves the frame), so
             * the overlay must be synced here.
             */

            syncOverlay(
                null
            )

            return@CameraAnalyzer
        }

        // ---------------------------------------------
        // Calculate distance
        // ---------------------------------------------

        val distance =
            distanceEngine.calculateDistance(
                width.toDouble()
            )

        // ---------------------------------------------
        // Get status
        // ---------------------------------------------

        val status =
            distanceEngine.getStatus(
                distance
            )

        Log.d(
            TAG,
            "Distance result: " +
                "distance=$distance cm " +
                "status=$status"
        )

        // ---------------------------------------------
        // Warning engine
        // ---------------------------------------------

        warningController.update(
            distance
        )

        syncOverlay(
            distance
        )
    }


            // -----------------------------------------
            // Create ImageReader
            // -----------------------------------------

            imageReader =
                ImageReader.newInstance(
                    IMAGE_WIDTH,
                    IMAGE_HEIGHT,
                    ImageFormat.YUV_420_888,
                    3
                )

            imageReader?.setOnImageAvailableListener(
                { reader ->

                    try {

                        /*
                         * acquireLatestImage() is important.
                         *
                         * We don't want to process old
                         * frames when ML Kit is still
                         * processing the previous frame.
                         */

                        val image =
                            reader.acquireLatestImage()

                        if (image == null) {
                            return@setOnImageAvailableListener
                        }

                        cameraAnalyzer?.analyze(
                            image
                        )

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "ImageReader processing error",
                            e
                        )
                    }

                },
                cameraHandler
            )

            // -----------------------------------------
            // Open camera
            // -----------------------------------------

            Log.d(
                TAG,
                "Opening native camera..."
            )

            cameraManager.openCamera(
                cameraId,
                cameraStateCallback,
                cameraHandler
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "SecurityException while opening camera",
                e
            )

            isCameraStarting = false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to start camera",
                e
            )

            isCameraStarting = false

            // Most commonly CAMERA_IN_USE
            // while another app holds the
            // camera. Retry automatically.

            scheduleCameraRetry()
        }
    }

    // --------------------------------------------------
    // Sync overlay with the warning state
    // --------------------------------------------------

    private fun syncOverlay(
        distance: Double?
    ) {

        val warningActive =
            warningController.isWarningVisible()

        Log.d(
            TAG,
            "Warning state: " +
                "active=$warningActive"
        )

        if (warningActive) {

            /*
             * Prefer the live distance. While the
             * face is undetectable, fall back to
             * the last known reading.
             */

            val displayDistance =
                distance
                    ?: warningController.getLastKnownDistance()

            overlayManager.show(
                displayDistance
            )

            overlayManager.updateDistance(
                displayDistance
            )

        } else {

            overlayManager.hide()
        }
    }

    // --------------------------------------------------
    // Schedule camera retry
    //
    // Another app can temporarily take the camera
    // (Android evicts lower priority clients).
    // Monitoring resumes automatically once the
    // camera becomes available again.
    // --------------------------------------------------

    private fun scheduleCameraRetry() {

        if (
            isShuttingDown ||
            isRetryScheduled ||
            isCameraRunning ||
            isCameraStarting
        ) {

            return
        }

        if (
            cameraHandler == null
        ) {

            startCameraThread()

            if (
                cameraHandler == null
            ) {

                return
            }
        }

        isRetryScheduled =
            true

        Log.d(
            TAG,
            "Camera unavailable. " +
                "Retrying in ${CAMERA_RETRY_DELAY_MS}ms"
        )

        cameraHandler?.postDelayed(
            {

                isRetryScheduled =
                    false

                if (
                    isShuttingDown ||
                    isCameraRunning ||
                    isCameraStarting
                ) {

                    return@postDelayed
                }

                Log.d(
                    TAG,
                    "Retrying camera start..."
                )

                startCamera()
            },
            CAMERA_RETRY_DELAY_MS
        )
    }

    // --------------------------------------------------
    // Find front camera
    // --------------------------------------------------

    private fun findFrontCamera():
        String? {

        try {

            for (
                cameraId in cameraManager.cameraIdList
            ) {

                val characteristics =
                    cameraManager.getCameraCharacteristics(
                        cameraId
                    )

                val lensFacing =
                    characteristics.get(
                        CameraCharacteristics.LENS_FACING
                    )

                if (
                    lensFacing ==
                    CameraCharacteristics.LENS_FACING_FRONT
                ) {

                    return cameraId
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to find front camera",
                e
            )
        }

        return null
    }

    // --------------------------------------------------
    // Camera state callback
    // --------------------------------------------------

    private val cameraStateCallback =
        object : CameraDevice.StateCallback() {

            override fun onOpened(
                camera: CameraDevice
            ) {

                Log.d(
                    TAG,
                    "Native camera OPENED"
                )

                cameraDevice =
                    camera

                isCameraStarting =
                    false

                isCameraRunning =
                    true

                createCaptureSession()
            }

            override fun onDisconnected(
                camera: CameraDevice
            ) {

                Log.w(
                    TAG,
                    "Native camera DISCONNECTED"
                )

                camera.close()

                if (
                    cameraDevice === camera
                ) {
                    cameraDevice = null
                }

                captureSession = null

                isCameraStarting =
                    false

                isCameraRunning =
                    false

                // Another app took the camera.
                // Resume automatically once
                // it becomes available again.

                scheduleCameraRetry()
            }

            override fun onError(
                camera: CameraDevice,
                error: Int
            ) {

                Log.e(
                    TAG,
                    "Native camera ERROR = $error"
                )

                camera.close()

                if (
                    cameraDevice === camera
                ) {
                    cameraDevice = null
                }

                captureSession = null

                isCameraStarting =
                    false

                isCameraRunning =
                    false

                scheduleCameraRetry()
            }

            override fun onClosed(
                camera: CameraDevice
            ) {

                Log.d(
                    TAG,
                    "Native camera CLOSED"
                )

                if (
                    cameraDevice === camera
                ) {
                    cameraDevice = null
                }

                isCameraRunning =
                    false
            }
        }

    // --------------------------------------------------
    // Create capture session
    // --------------------------------------------------

    private fun createCaptureSession() {

        val camera =
            cameraDevice

        if (camera == null) {

            Log.e(
                TAG,
                "Cannot create capture session: " +
                    "cameraDevice is null"
            )

            return
        }

        val surface =
            imageReader?.surface

        if (surface == null) {

            Log.e(
                TAG,
                "Cannot create capture session: " +
                    "ImageReader surface is null"
            )

            return
        }

        try {

            Log.d(
                TAG,
                "Creating native capture session..."
            )

            camera.createCaptureSession(
                listOf(surface),
                object :
                    CameraCaptureSession.StateCallback() {

                    override fun onConfigured(
                        session:
                            CameraCaptureSession
                    ) {

                        Log.d(
                            TAG,
                            "Native capture session CONFIGURED"
                        )

                        captureSession =
                            session

                        try {

                            val request =
                                camera.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                )

                            request.addTarget(
                                surface
                            )

                            request.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )

                            /*
                             * Start continuous frame capture.
                             */

                            session.setRepeatingRequest(
                                request.build(),
                                null,
                                cameraHandler
                            )

                            Log.d(
                                TAG,
                                "Native camera repeating request STARTED"
                            )

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Failed to start repeating request",
                                e
                            )
                        }
                    }

                    override fun onConfigureFailed(
                        session:
                            CameraCaptureSession
                    ) {

                        Log.e(
                            TAG,
                            "Native capture session " +
                                "CONFIGURATION FAILED"
                        )

                        captureSession =
                            null

                        camera.close()

                        if (
                            cameraDevice === camera
                        ) {
                            cameraDevice =
                                null
                        }

                        isCameraRunning =
                            false

                        scheduleCameraRetry()
                    }

                    override fun onClosed(
                        session:
                            CameraCaptureSession
                    ) {

                        Log.d(
                            TAG,
                            "Native capture session CLOSED"
                        )

                        if (
                            captureSession === session
                        ) {
                            captureSession =
                                null
                        }
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to create capture session",
                e
            )
        }
    }

    // --------------------------------------------------
    // Camera thread
    // --------------------------------------------------

    private fun startCameraThread() {

        if (cameraThread != null) {
            return
        }

        Log.d(
            TAG,
            "Starting camera background thread"
        )

        cameraThread =
            HandlerThread(
                "EyeGuardCameraThread"
            )

        cameraThread?.start()

        cameraHandler =
            Handler(
                cameraThread!!.looper
            )
    }

    // --------------------------------------------------
    // Stop camera
    // --------------------------------------------------

    private fun stopCamera() {

        Log.d(
            TAG,
            "Stopping native camera"
        )

        try {

            captureSession?.stopRepeating()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to stop repeating request",
                e
            )
        }

        try {

            captureSession?.abortCaptures()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to abort captures",
                e
            )
        }

        try {

            captureSession?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to close capture session",
                e
            )
        }

        captureSession =
            null

        try {

            cameraDevice?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to close camera device",
                e
            )
        }

        cameraDevice =
            null

        try {

            imageReader?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to close ImageReader",
                e
            )
        }

        imageReader =
            null

        try {

            cameraAnalyzer?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to close CameraAnalyzer",
                e
            )
        }

        cameraAnalyzer =
            null

        isCameraStarting =
            false

        isCameraRunning =
            false

        cameraThread?.quitSafely()

        cameraThread =
            null

        cameraHandler =
            null

        Log.d(
            TAG,
            "Native camera stopped"
        )
    }

    // --------------------------------------------------
    // Service destroy
    // --------------------------------------------------


override fun onDestroy() {

    isShuttingDown =
        true

    Log.d(
        TAG,
        "Service onDestroy()"
    )

    // ---------------------------------------------
    // Remove warning overlay
    // ---------------------------------------------

    try {

        overlayManager.destroy()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Overlay cleanup failed",
            e
        )
    }

    // ---------------------------------------------
    // Stop warning controller
    // ---------------------------------------------

    warningController.close()

    // ---------------------------------------------
    // Stop native camera
    // ---------------------------------------------

    stopCamera()

    Log.d(
        TAG,
        "Eye Guard service destroyed"
    )

    super.onDestroy()
}

    // --------------------------------------------------
    // Binding
    // --------------------------------------------------

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    // --------------------------------------------------
    // Notification channel
    // --------------------------------------------------

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Eye Guard Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        "Shows when Eye Guard is monitoring"

                    setShowBadge(false)
                }

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    // --------------------------------------------------
    // Notification
    // --------------------------------------------------

    private fun createNotification():
        Notification {

        // ---------------------------------------------
        // Open the app when the notification
        // is tapped.
        // ---------------------------------------------

        val launchIntent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val builder =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                Notification.Builder(
                    this,
                    CHANNEL_ID
                )

            } else {

                Notification.Builder(
                    this
                )
            }

        builder
            .setContentIntent(
                pendingIntent
            )
            .setContentTitle(
                "Eye Guard"
            )
            .setContentText(
                "Eye Guard is monitoring"
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_view
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            builder.setForegroundServiceBehavior(
                Notification.FOREGROUND_SERVICE_IMMEDIATE
            )
        }

        return builder.build()
    }
}