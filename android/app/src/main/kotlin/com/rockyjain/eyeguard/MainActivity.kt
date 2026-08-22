package com.rockyjain.eyeguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.net.Uri
import android.provider.Settings

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL =
            "com.rockyjain.eyeguard/monitoring"

        private const val NOTIFICATION_PERMISSION_REQUEST =
            100
    }

    override fun configureFlutterEngine(
        flutterEngine: FlutterEngine
    ) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                "startMonitoring" -> {

                    startMonitoringService()

                    result.success(true)
                }

                "stopMonitoring" -> {

                    stopMonitoringService()

                    result.success(true)
                }

                "requestNotificationPermission" -> {

                    requestNotificationPermission()

                    result.success(true)
                }
             

"setCalibration" -> {

    val faceWidth =
        call.argument<Double>("faceWidth")

    if (
        faceWidth == null ||
        faceWidth <= 0
    ) {
        result.error(
            "INVALID_CALIBRATION",
            "Invalid face width",
            null
        )

        return@setMethodCallHandler
    }

    getSharedPreferences(
        "eye_guard_preferences",
        MODE_PRIVATE
    )
        .edit()
        .putFloat(
            "reference_face_width",
            faceWidth.toFloat()
        )
        .putBoolean(
            "calibration_complete",
            true
        )
        .apply()

    android.util.Log.d(
        "EyeGuardActivity",
        "Calibration permanently saved: " +
            "$faceWidth px"
    )

    result.success(true)
}

"requestOverlayPermission" -> {

    if (
        !Settings.canDrawOverlays(this)
    ) {

        val intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse(
                    "package:$packageName"
                )
            )

        startActivity(intent)
    }

    result.success(
        Settings.canDrawOverlays(this)
    )
}


"getCalibrationStatus" -> {

    val preferences =
        getSharedPreferences(
            "eye_guard_preferences",
            MODE_PRIVATE
        )

    val complete =
        preferences.getBoolean(
            "calibration_complete",
            false
        )

    result.success(complete)
}

"getCalibrationWidth" -> {

    val preferences =
        getSharedPreferences(
            "eye_guard_preferences",
            MODE_PRIVATE
        )

    val width =
        preferences.getFloat(
            "reference_face_width",
            0f
        )

    if (width > 0f) {

        result.success(
            width.toDouble()
        )

    } else {

        result.success(null)
    }
}


"resetCalibration" -> {

    getSharedPreferences(
        "eye_guard_preferences",
        MODE_PRIVATE
    )
        .edit()
        .remove("reference_face_width")
        .remove("calibration_complete")
        .apply()

    android.util.Log.d(
        "EyeGuardActivity",
        "Stored calibration deleted"
    )

    result.success(true)
}


"isMonitoringActive" -> {

    result.success(
        DistanceMonitorService
            .isServiceRunning
    )
}


                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun startMonitoringService() {

        val intent = Intent(
            this,
            DistanceMonitorService::class.java
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            ContextCompat.startForegroundService(
                this,
                intent
            )

        } else {

            startService(intent)
        }
    }

    private fun stopMonitoringService() {

        val intent = Intent(
            this,
            DistanceMonitorService::class.java
        )

        stopService(intent)
    }

    private fun requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }
}