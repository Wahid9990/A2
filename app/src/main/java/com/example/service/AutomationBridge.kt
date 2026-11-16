package com.example.service

import com.example.model.BotConfig
import com.example.model.BotLogEntry
import com.example.model.VisionAnalysisResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object AutomationBridge {

    private var accessibilityServiceRef: WeakReference<JumpAccessibilityService>? = null
    private val logIdGen = AtomicLong(1L)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isScreenCaptureActive = MutableStateFlow(false)
    val isScreenCaptureActive: StateFlow<Boolean> = _isScreenCaptureActive.asStateFlow()

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

    private val _isAutoLoopActive = MutableStateFlow(false)
    val isAutoLoopActive: StateFlow<Boolean> = _isAutoLoopActive.asStateFlow()

    private val _currentConfig = MutableStateFlow(BotConfig())
    val currentConfig: StateFlow<BotConfig> = _currentConfig.asStateFlow()

    private val _lastAnalysis = MutableStateFlow<VisionAnalysisResult?>(null)
    val lastAnalysis: StateFlow<VisionAnalysisResult?> = _lastAnalysis.asStateFlow()

    private val _logs = MutableStateFlow<List<BotLogEntry>>(emptyList())
    val logs: StateFlow<List<BotLogEntry>> = _logs.asStateFlow()

    private val _singleStepRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val singleStepRequests: SharedFlow<Unit> = _singleStepRequests.asSharedFlow()

    fun setAccessibilityService(service: JumpAccessibilityService?) {
        accessibilityServiceRef = service?.let { WeakReference(it) }
        _isAccessibilityConnected.value = service != null
        if (service != null) {
            appendLog("Accessibility gesture service connected")
        } else {
            _isAutoLoopActive.value = false
            appendLog("Accessibility gesture service disconnected", isError = true)
        }
    }

    fun setScreenCaptureActive(active: Boolean) {
        _isScreenCaptureActive.value = active
        if (!active) {
            _isAutoLoopActive.value = false
        }
    }

    fun setOverlayActive(active: Boolean) {
        _isOverlayActive.value = active
    }

    fun setAutoLoopActive(active: Boolean) {
        _isAutoLoopActive.value = active
        appendLog(if (active) "Auto-jump loop started" else "Auto-jump loop paused")
    }

    fun toggleAutoLoop() {
        setAutoLoopActive(!_isAutoLoopActive.value)
    }

    fun requestSingleStep() {
        _singleStepRequests.tryEmit(Unit)
    }

    fun updateConfig(config: BotConfig) {
        _currentConfig.value = config
    }

    fun updateAnalysis(result: VisionAnalysisResult) {
        _lastAnalysis.value = result
    }

    fun performHoldGesture(
        x: Float,
        y: Float,
        durationMs: Long,
        onComplete: (Boolean) -> Unit
    ) {
        val service = accessibilityServiceRef?.get()
        if (service == null) {
            appendLog("Cannot dispatch touch: Accessibility Service disabled", isError = true)
            onComplete(false)
            return
        }
        service.performHoldGesture(x, y, durationMs, onComplete)
    }

    fun appendLog(message: String, isError: Boolean = false) {
        val entry = BotLogEntry(
            id = logIdGen.getAndIncrement(),
            timestamp = timeFormat.format(Date()),
            message = message,
            isError = isError
        )
        _logs.update { current ->
            (listOf(entry) + current).take(40)
        }
    }
}
