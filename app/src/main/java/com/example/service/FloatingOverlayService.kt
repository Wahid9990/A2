package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private lateinit var telemetryText: TextView
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
            setPadding(0, dp(4), 0, dp(8))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val stepBtn = createPillButton("Step", Color.rgb(29, 45, 70), Color.rgb(241, 245, 249), dp(8)).apply {
            setOnClickListener {
                AutomationBridge.requestSingleStep()
            }
        }

        autoButton = createPillButton("Auto", Color.rgb(16, 185, 129), Color.rgb(2, 38, 25), dp(8)).apply {
            setOnClickListener {
                AutomationBridge.toggleAutoLoop()
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(1))
        }

        buttonRow.addView(stepBtn)
        buttonRow.addView(spacer)
        buttonRow.addView(autoButton)

        container.addView(headerRow)
        container.addView(telemetryText)
        container.addView(buttonRow)

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
            y = dp(120)
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
            minHeight = (36 * resources.displayMetrics.density).roundToInt()
            minimumHeight = minHeight
            minWidth = (64 * resources.displayMetrics.density).roundToInt()
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
                        analysis.statusMessage.take(34)
                    }
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
