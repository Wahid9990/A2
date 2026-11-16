package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.vision.BlockVisionAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var stepJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AutomationBridge.appendLog("MediaProjection session stopped by system")
            stopCaptureResources()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode != Activity.RESULT_OK || dataIntent == null) {
                    AutomationBridge.appendLog("MediaProjection permission denied", isError = true)
                    stopSelf()
                    return START_NOT_STICKY
                }

                startAsForeground()
                initializeProjection(resultCode, dataIntent)
            }

            ACTION_STOP -> {
                AutomationBridge.appendLog("Screen capture service stopped")
                stopCaptureResources()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializeProjection(resultCode: Int, data: Intent) {
        stopCaptureResources()
        resolveScreenMetrics()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            AutomationBridge.appendLog("Failed to acquire MediaProjection instance", isError = true)
            stopSelf()
            return
        }

        projection.registerCallback(projectionCallback, null)
        mediaProjection = projection

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection.createVirtualDisplay(
            "JumpAssistCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        AutomationBridge.setScreenCaptureActive(true)
        AutomationBridge.appendLog("Screen capture started (${screenWidth}x${screenHeight})")

        startWorkers()
    }

    private fun resolveScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width().coerceAtLeast(360)
            screenHeight = bounds.height().coerceAtLeast(640)
            screenDensity = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels.coerceAtLeast(360)
            screenHeight = metrics.heightPixels.coerceAtLeast(640)
            screenDensity = metrics.densityDpi
        }
    }

    private fun startWorkers() {
        loopJob?.cancel()
        stepJob?.cancel()

        loopJob = serviceScope.launch {
            while (isActive) {
                if (AutomationBridge.isAutoLoopActive.value) {
                    val waitMs = executeSingleCycle()
                    delay(waitMs)
                } else {
                    delay(180L)
                }
            }
        }

        stepJob = serviceScope.launch {
            AutomationBridge.singleStepRequests.collect {
                executeSingleCycle(requireStability = false)
            }
        }
    }

    private suspend fun executeSingleCycle(requireStability: Boolean = true): Long {
        val config = AutomationBridge.currentConfig.value
        val overlayRect = AutomationBridge.overlayBounds.value

        var stableResult = analyzeLatestFrame(config, overlayRect) ?: return 300L

        if (requireStability && AutomationBridge.isAutoLoopActive.value) {
            var checks = 0
            while (checks < 6 && AutomationBridge.isAutoLoopActive.value) {
                delay(150L)
                val nextResult = analyzeLatestFrame(config, overlayRect)
                if (nextResult != null &&
                    stableResult.pawnFound && nextResult.pawnFound &&
                    stableResult.blockFound && nextResult.blockFound &&
                    kotlin.math.abs(stableResult.rawDistancePx - nextResult.rawDistancePx) <= 8f &&
                    kotlin.math.abs(stableResult.pawnCenterX - nextResult.pawnCenterX) <= 6f
                ) {
                    stableResult = nextResult
                    break
                }
                if (nextResult != null) {
                    stableResult = nextResult
                }
                checks++
            }
        }

        AutomationBridge.updateAnalysis(stableResult)

        if (!stableResult.pawnFound || !stableResult.blockFound) {
            AutomationBridge.appendLog(stableResult.statusMessage, isError = true)
            return 550L
        }

        val tapX = (screenWidth * config.tapXFraction).coerceIn(40f, screenWidth - 40f)
        val tapY = (screenHeight * config.tapYFraction).coerceIn(40f, screenHeight - 40f)
        val holdMs = stableResult.computedDurationMs

        AutomationBridge.appendLog(
            "D=${stableResult.rawDistancePx.roundToInt()}px W=${stableResult.blockBounds?.widthPx?.roundToInt() ?: 0}px → Hold ${holdMs}ms"
        )

        val completed = suspendCancellableCoroutine { cont ->
            AutomationBridge.performHoldGesture(tapX, tapY, holdMs) { ok ->
                if (cont.isActive) cont.resume(ok)
            }
        }

        if (!completed) {
            AutomationBridge.appendLog("Gesture dispatch failed or cancelled", isError = true)
            return 800L
        }

        return config.jumpCooldownMs.coerceAtLeast(600L)
    }

    private fun analyzeLatestFrame(
        config: com.example.model.BotConfig,
        overlayRect: android.graphics.Rect?
    ): com.example.model.VisionAnalysisResult? {
        val bitmap = acquireLatestBitmap() ?: return null
        return try {
            BlockVisionAnalyzer.analyze(bitmap, config, overlayRect)
        } finally {
            bitmap.recycle()
        }
    }

    private fun acquireLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride

            val rawBitmap = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(buffer)

            if (paddedWidth == screenWidth) {
                rawBitmap
            } else {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                rawBitmap.recycle()
                cropped
            }
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }
    }

    private fun stopCaptureResources() {
        loopJob?.cancel()
        stepJob?.cancel()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        AutomationBridge.setScreenCaptureActive(false)
    }

    override fun onDestroy() {
        stopCaptureResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.action.START_CAPTURE"
        const val ACTION_STOP = "com.example.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        private const val CHANNEL_ID = "jump_assist_capture_channel"
        private const val NOTIFICATION_ID = 4102

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
