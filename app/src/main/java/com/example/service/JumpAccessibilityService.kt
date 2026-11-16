package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class JumpAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationBridge.setAccessibilityService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Frame vision uses MediaProjection; accessibility event stream is intentionally minimal.
    }

    override fun onInterrupt() {
        AutomationBridge.setAutoLoopActive(false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AutomationBridge.setAccessibilityService(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AutomationBridge.setAccessibilityService(null)
        super.onDestroy()
    }

    fun performHoldGesture(
        x: Float,
        y: Float,
        durationMs: Long,
        onComplete: (Boolean) -> Unit
    ) {
        val clampedDuration = durationMs.coerceIn(50L, 4000L)
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, clampedDuration)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete(false)
                }
            },
            null
        )

        if (!dispatched) {
            onComplete(false)
        }
    }
}
