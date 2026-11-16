package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private lateinit var telemetryText: TextView
    private lateinit var trainStatusText: TextView
    private lateinit var autoButton: Button
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlayHud()
        observeBridgeState()
        AutomationBridge.setOverlayActive(true)
        AutomationBridge.appendLog("Floating HUD overlay launched")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayHud() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        val containerBg = GradientDrawable().apply {
            setColor(Color.argb(235, 11, 19, 32))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(34, 211, 238))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = containerBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "JumpAssist HUD"
            setTextColor(Color.rgb(34, 211, 238))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.rgb(244, 63, 94))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener {
                stopSelf()
            }
        }

        headerRow.addView(titleView)
        headerRow.addView(closeBtn)

        telemetryText = TextView(this).apply {
            text = "D: -- px  |  Hold: -- ms"
            setTextColor(Color.rgb(241, 245, 249))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(2))
        }

        trainStatusText = TextView(this).apply {
            text = "Train: READY"
            setTextColor(Color.rgb(16, 185, 129))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val stepBtn = createPillButton("Step", Color.rgb(29, 45, 70), Color.rgb(241, 245, 249), dp(8)).apply {
            setOnClickListener {
                if (ensureServicesReadyForTouch()) {
                    AutomationBridge.requestSingleStep()
                }
            }
        }

        autoButton = createPillButton("Auto", Color.rgb(16, 185, 129), Color.rgb(2, 38, 25), dp(8)).apply {
            setOnClickListener {
                if (AutomationBridge.isAutoLoopActive.value) {
                    AutomationBridge.setAutoLoopActive(false)
                } else if (ensureServicesReadyForTouch()) {
                    AutomationBridge.setAutoLoopActive(true)
                }
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(1))
        }

        buttonRow.addView(stepBtn)
        buttonRow.addView(spacer)
        buttonRow.addView(autoButton)

        // Manual Self-Train nudge pills for immediate correction if user spots short/long jump
        val trainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val shortBtn = createSmallFeedbackButton("◀ Too Short", Color.rgb(51, 35, 12), Color.rgb(251, 191, 36), dp(6)).apply {
            setOnClickListener {
                if (ensureServicesReadyForTouch()) {
                    AutomationBridge.requestManualFeedback(isShort = true)
                }
            }
        }

        val trainSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(1))
        }

        val longBtn = createSmallFeedbackButton("Too Far ▶", Color.rgb(66, 20, 32), Color.rgb(251, 113, 133), dp(6)).apply {
            setOnClickListener {
                if (ensureServicesReadyForTouch()) {
                    AutomationBridge.requestManualFeedback(isShort = false)
                }
            }
        }

        trainRow.addView(shortBtn)
        trainRow.addView(trainSpacer)
        trainRow.addView(longBtn)

        container.addView(headerRow)
        container.addView(telemetryText)
        container.addView(trainStatusText)
        container.addView(buttonRow)
        container.addView(trainRow)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(32)
        }

        fun publishBounds() {
            val loc = IntArray(2)
            container.getLocationOnScreen(loc)
            if (container.width > 0 && container.height > 0) {
                AutomationBridge.setOverlayBounds(
                    Rect(loc[0], loc[1], loc[0] + container.width, loc[1] + container.height)
                )
            }
        }

        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            publishBounds()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        headerRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    windowManager?.updateViewLayout(container, params)
                    publishBounds()
                    true
                }
                else -> false
            }
        }

        rootView = container
        windowManager?.addView(container, params)
    }

    private fun createPillButton(label: String, bgColor: Int, textColor: Int, horizontalPad: Int): Button {
        val drawable = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = horizontalPad * 1.5f
        }
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 11f
            isAllCaps = false
            background = drawable
            setPadding(horizontalPad * 2, horizontalPad / 2, horizontalPad * 2, horizontalPad / 2)
            minHeight = (34 * resources.displayMetrics.density).roundToInt()
            minimumHeight = minHeight
            minWidth = (64 * resources.displayMetrics.density).roundToInt()
        }
    }

    private fun createSmallFeedbackButton(label: String, bgColor: Int, textColor: Int, horizontalPad: Int): Button {
        val drawable = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = horizontalPad * 1.2f
        }
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 10f
            isAllCaps = false
            background = drawable
            setPadding(horizontalPad + 4, horizontalPad / 3, horizontalPad + 4, horizontalPad / 3)
            minHeight = (28 * resources.displayMetrics.density).roundToInt()
            minimumHeight = minHeight
            minWidth = (56 * resources.displayMetrics.density).roundToInt()
        }
    }

    private fun observeBridgeState() {
        serviceScope.launch {
            AutomationBridge.lastAnalysis.collect { analysis ->
                if (analysis != null) {
                    val d = analysis.rawDistancePx.roundToInt()
                    val w = analysis.blockBounds?.widthPx?.roundToInt() ?: 0
                    val t = analysis.computedDurationMs
                    telemetryText.text = if (analysis.blockFound) {
                        "D: ${d}px  W: ${w}px  →  ${t}ms"
                    } else {
                        analysis.statusMessage.take(32)
                    }
                }
            }
        }

        serviceScope.launch {
            combine(
                AutomationBridge.lastTrainingBanner,
                AutomationBridge.isAccessibilityConnected,
                AutomationBridge.isScreenCaptureActive
            ) { banner, accessibilityConnected, captureActive ->
                Triple(banner, accessibilityConnected, captureActive)
            }.collect { (banner, accessibilityConnected, captureActive) ->
                if (!accessibilityConnected) {
                    trainStatusText.text = "⚠ Touch OFF — Tap Auto to Re-enable"
                    trainStatusText.setTextColor(Color.rgb(251, 191, 36))
                } else if (!captureActive) {
                    trainStatusText.text = "⚠ Capture OFF — Tap Auto to Grant"
                    trainStatusText.setTextColor(Color.rgb(251, 191, 36))
                } else {
                    val cfg = AutomationBridge.currentConfig.value
                    trainStatusText.text = banner ?: String.format(
                        Locale.US,
                        "Train:%s · k=%.2f b=%.0f",
                        if (cfg.autoTrainEnabled) "ON" else "OFF",
                        cfg.kFactor,
                        cfg.offsetMs
                    )
                    trainStatusText.setTextColor(Color.rgb(16, 185, 129))
                }
            }
        }

        serviceScope.launch {
            AutomationBridge.isAutoLoopActive.collect { active ->
                val bg = autoButton.background as? GradientDrawable
                if (active) {
                    autoButton.text = "Pause"
                    autoButton.setTextColor(Color.rgb(43, 24, 0))
                    bg?.setColor(Color.rgb(245, 158, 11))
                } else {
                    autoButton.text = "Auto"
                    autoButton.setTextColor(Color.rgb(2, 38, 25))
                    bg?.setColor(Color.rgb(16, 185, 129))
                }
            }
        }
    }

    private fun ensureServicesReadyForTouch(): Boolean {
        if (!AutomationBridge.isAccessibilityConnected.value) {
            Toast.makeText(
                this,
                "Turn JumpAssist Accessibility OFF → ON to enable touch!",
                Toast.LENGTH_LONG
            ).show()
            AutomationBridge.appendLog(
                "HUD: Accessibility unbound — opening Settings (toggle OFF → ON)",
                isError = true
            )
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            return false
        }
        if (!AutomationBridge.isScreenCaptureActive.value) {
            Toast.makeText(
                this,
                "Grant Screen Capture in JumpAssist app first!",
                Toast.LENGTH_LONG
            ).show()
            AutomationBridge.appendLog(
                "HUD: Screen Capture inactive — opening JumpAssist",
                isError = true
            )
            runCatching {
                packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent)
                }
            }
            return false
        }
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        rootView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        rootView = null
        AutomationBridge.setOverlayActive(false)
        AutomationBridge.appendLog("Floating HUD overlay closed")
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, FloatingOverlayService::class.java)
    }
}
