package com.rockyjain.eyeguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class OverlayManager(
    private val context: Context
) {

    companion object {

        private const val TAG =
            "EyeGuardOverlay"
    }

    private val windowManager =
        context.getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

    private var overlayView:
        View? = null

    private var distanceText:
        TextView? = null

    // --------------------------------------------------
    // Permission
    // --------------------------------------------------

    fun canDrawOverlays(): Boolean {

        return Settings.canDrawOverlays(context)
    }

    // --------------------------------------------------
    // Show
    // --------------------------------------------------

    fun show(distanceCm: Double?) {

        if (!canDrawOverlays()) {

            Log.w(
                TAG,
                "Overlay permission not granted"
            )

            return
        }

        // Already visible.
        // Just update distance.
        if (overlayView != null) {

            updateDistance(distanceCm)

            return
        }

        val root =
            createOverlayView(distanceCm)

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,

                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,

                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,

                android.graphics.PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or Gravity.START

        try {

            windowManager.addView(
                root,
                params
            )

            overlayView =
                root

            Log.d(
                TAG,
                "Full-screen warning overlay shown"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to show overlay",
                e
            )
        }
    }

    // --------------------------------------------------
    // Update distance
    // --------------------------------------------------

    fun updateDistance(
        distanceCm: Double?
    ) {

        val textView =
            distanceText
                ?: return

        val text =
            if (distanceCm != null) {

                "${distanceCm.roundToInt()} cm"

            } else {

                "-- cm"
            }

        textView.post {

            textView.text =
                text
        }
    }

    // --------------------------------------------------
    // Hide
    // --------------------------------------------------

    fun hide() {

        val view =
            overlayView
                ?: return

        try {

            windowManager.removeView(
                view
            )

            Log.d(
                TAG,
                "Full-screen warning overlay hidden"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to hide overlay",
                e
            )
        }

        overlayView =
            null

        distanceText =
            null
    }

    // --------------------------------------------------
    // Cleanup
    // --------------------------------------------------

    fun destroy() {

        hide()
    }

    // --------------------------------------------------
    // UI
    // --------------------------------------------------

    private fun createOverlayView(
        distanceCm: Double?
    ): View {

        val root =
            LinearLayout(context)

        root.orientation =
            LinearLayout.VERTICAL

        root.gravity =
            Gravity.CENTER

        root.setPadding(
            dp(32),
            dp(32),
            dp(32),
            dp(32)
        )

        root.setBackgroundColor(
            Color.argb(
                245,
                130,
                0,
                0
            )
        )

        // ----------------------------------------------
        // Warning icon
        // ----------------------------------------------

        val icon =
            TextView(context)

        icon.text =
            "⚠️"

        icon.textSize =
            64f

        icon.gravity =
            Gravity.CENTER

        root.addView(
            icon,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ----------------------------------------------
        // Title
        // ----------------------------------------------

        val title =
            TextView(context)

        title.text =
            "TOO CLOSE!"

        title.textSize =
            38f

        title.setTextColor(
            Color.WHITE
        )

        title.typeface =
            Typeface.DEFAULT_BOLD

        title.gravity =
            Gravity.CENTER

        title.setPadding(
            0,
            dp(16),
            0,
            dp(12)
        )

        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ----------------------------------------------
        // Instruction card
        // ----------------------------------------------

        val card =
            LinearLayout(context)

        card.orientation =
            LinearLayout.VERTICAL

        card.gravity =
            Gravity.CENTER

        card.setPadding(
            dp(24),
            dp(24),
            dp(24),
            dp(24)
        )

        val cardBackground =
            GradientDrawable()

        cardBackground.setColor(
            Color.WHITE
        )

        cardBackground.cornerRadius =
            dp(24).toFloat()

        card.background =
            cardBackground

        // ----------------------------------------------
        // Instruction
        // ----------------------------------------------

        val instruction =
            TextView(context)

        instruction.text =
            "Please move the phone\nfarther away."

        instruction.textSize =
            26f

        instruction.setTextColor(
            Color.rgb(
                40,
                40,
                40
            )
        )

        instruction.typeface =
            Typeface.DEFAULT_BOLD

        instruction.gravity =
            Gravity.CENTER

        card.addView(
            instruction,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ----------------------------------------------
        // Distance
        // ----------------------------------------------

        val distance =
            TextView(context)

        distance.text =
            if (distanceCm != null) {
                "${distanceCm.roundToInt()} cm"
            } else {
                "-- cm"
            }

        distance.textSize =
            42f

        distance.setTextColor(
            Color.rgb(
                190,
                0,
                0
            )
        )

        distance.typeface =
            Typeface.DEFAULT_BOLD

        distance.gravity =
            Gravity.CENTER

        distance.setPadding(
            0,
            dp(24),
            0,
            dp(8)
        )

        distanceText =
            distance

        card.addView(
            distance,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ----------------------------------------------
        // Footer
        // ----------------------------------------------

        val footer =
            TextView(context)

        footer.text =
            "Move back to continue using your phone"

        footer.textSize =
            18f

        footer.setTextColor(
            Color.DKGRAY
        )

        footer.gravity =
            Gravity.CENTER

        card.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val cardParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

        cardParams.setMargins(
            dp(24),
            dp(24),
            dp(24),
            dp(24)
        )

        root.addView(
            card,
            cardParams
        )

        // ----------------------------------------------
        // Prevent accidental interaction with the
        // underlying application.
        // ----------------------------------------------

        root.setOnTouchListener { _, _ ->
            true
        }

        return root
    }

    // --------------------------------------------------
    // dp helper
    // --------------------------------------------------

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                context.resources.displayMetrics.density
            ).roundToInt()
    }
}