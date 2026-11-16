package com.example.training

import android.os.Build
import com.example.model.BotConfig
import com.example.model.BotLogEntry
import com.example.model.FormulaMode
import com.example.model.JumpOutcome
import com.example.model.JumpTelemetryRecord
import com.example.model.VisionAnalysisResult
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

object JumpTrainer {

    private const val MIN_K = 0.85f
    private const val MAX_K = 2.15f
    private const val MIN_B = -15f
    private const val MAX_B = 75f
    private const val ANCHOR_K = 1.48f
    private const val MAX_AUTO_K_STEP = 0.055f

    /**
     * Measures how far the pawn landed along the exact jump vector from pre-jump pawn to
     * pre-jump target block center. 1.0 = dead center, < 1.0 = short, > 1.0 = past center / overshoot.
     */
    fun computeLandingRatio(
        pre: VisionAnalysisResult,
        landing: VisionAnalysisResult?
    ): Float? {
        if (landing == null || !pre.pawnFound || !pre.blockFound || !landing.pawnFound) return null
        val block = pre.blockBounds ?: return null
        val vx = block.center.x - pre.pawnCenterX
        val vy = block.center.y - pre.pawnBaseY
        val distSq = vx * vx + vy * vy
        val dist = pre.rawDistancePx
        if (dist < 40f || distSq < 1600f) return null

        val ux = landing.pawnCenterX - pre.pawnCenterX
        val uy = landing.pawnBaseY - pre.pawnBaseY
        val ratio = (ux * vx + uy * vy) / distSq

        // Reject completely unrelated jumps (e.g. pawn reset / UI overlap perpendicular to jump axis)
        val cross = abs(ux * vy - uy * vx) / dist
        val perpFraction = cross / dist
        if (ratio !in 0.25f..1.85f || perpFraction > 0.65f) return null
        return ratio
    }

    fun classifyOutcome(
        pre: VisionAnalysisResult,
        landingRatio: Float?,
        postSettled: VisionAnalysisResult?
    ): JumpOutcome {
        if (postSettled != null && !postSettled.pawnFound) {
            return when {
                landingRatio != null && landingRatio < 0.94f -> JumpOutcome.OUT_SHORT
                landingRatio != null && landingRatio > 1.06f -> JumpOutcome.OUT_OVERSHOOT
                else -> JumpOutcome.OUT_UNKNOWN
            }
        }

        if (landingRatio != null) {
            // A pawn that survived on the block cannot physically land at r < 0.72;
            // values below 0.72 after surviving mean camera pan already slid the block back.
            if (landingRatio < 0.72f && postSettled?.pawnFound == true) {
                return JumpOutcome.CENTER
            }
            return when {
                landingRatio < 0.89f -> JumpOutcome.SHORT
                landingRatio <= 1.11f -> JumpOutcome.CENTER
                else -> JumpOutcome.PAST_CENTER
            }
        }

        // Fallback if post-settled stayed right on the pre-jump platform (very short press)
        val preBlk = pre.blockBounds
        val postBlk = postSettled?.blockBounds
        if (postSettled != null && postSettled.pawnFound && preBlk != null && postBlk != null) {
            val samePawn = hypot(postSettled.pawnCenterX - pre.pawnCenterX, postSettled.pawnBaseY - pre.pawnBaseY) < 28f
            val sameBlock = hypot(postBlk.center.x - preBlk.center.x, postBlk.center.y - preBlk.center.y) < 28f
            if (samePawn && sameBlock) {
                return JumpOutcome.SHORT
            }
        }

        return if (postSettled?.pawnFound == true) JumpOutcome.CENTER else JumpOutcome.UNKNOWN
    }

    /**
     * Self-training online update step using bounded normalized LMS + clean-only damped regression.
     * - Rejects impossible camera-panned ratios (r < 0.72 when pawn survived).
     * - Caps per-jump k delta to prevent runaway spikes.
     * - Self-recovers on OUT / OUT_UNKNOWN so the same fatal hold is never repeated blindly.
     */
    fun applyOnlineUpdate(
        config: BotConfig,
        dNorm: Float,
        holdMs: Long,
        outcome: JumpOutcome,
        landingRatio: Float?,
        history: List<JumpTelemetryRecord>
    ): Pair<BotConfig, String> {
        if (!config.autoTrainEnabled || dNorm < 80f) {
            return config to "Self-train skipped"
        }

        var newK = config.kFactor
        var newB = config.offsetMs
        val isFatalOut = outcome == JumpOutcome.OUT_SHORT ||
            outcome == JumpOutcome.OUT_OVERSHOOT ||
            outcome == JumpOutcome.OUT_UNKNOWN
        var allowLargeRecovery = false
        var note = "Keep"

        when {
            outcome == JumpOutcome.OUT_SHORT -> {
                if (landingRatio != null && landingRatio in 0.45f..0.95f) {
                    val safeRatio = landingRatio.coerceIn(0.68f, 0.92f)
                    val idealHoldMs = (holdMs.toFloat() / safeRatio)
                        .coerceIn(config.minPressMs.toFloat(), config.maxPressMs.toFloat())
                    val deltaMs = ((idealHoldMs - holdMs.toFloat()) * 0.48f).coerceAtLeast(18f)
                    newK += (deltaMs * 0.85f) / dNorm.coerceAtLeast(150f)
                    newB += deltaMs * 0.15f
                    note = String.format(Locale.US, "Out-short LMS r=%.2f +%.0fms", landingRatio, deltaMs)
                } else {
                    newK += 0.045f
                    newB += 6f
                    note = "Out-short guard (+k,+b)"
                }
            }

            outcome == JumpOutcome.OUT_OVERSHOOT -> {
                allowLargeRecovery = true
                if (landingRatio != null && landingRatio in 1.06f..1.65f) {
                    val safeRatio = landingRatio.coerceIn(1.07f, 1.45f)
                    val idealHoldMs = (holdMs.toFloat() / safeRatio)
                        .coerceIn(config.minPressMs.toFloat(), config.maxPressMs.toFloat())
                    val deltaMs = ((idealHoldMs - holdMs.toFloat()) * 0.50f).coerceAtMost(-20f)
                    newK += (deltaMs * 0.85f) / dNorm.coerceAtLeast(150f)
                    newB += deltaMs * 0.15f
                    note = String.format(Locale.US, "Out-far LMS r=%.2f %.0fms", landingRatio, deltaMs)
                } else {
                    newK -= 0.055f
                    newB -= 6f
                    note = "Out-far guard (-k,-b)"
                }
            }

            outcome == JumpOutcome.OUT_UNKNOWN -> {
                allowLargeRecovery = true
                val effK = holdMs.toFloat() / dNorm.coerceAtLeast(150f)
                val prev = history.firstOrNull()
                val sameSpotRepeatOut = prev != null &&
                    (prev.outcome == JumpOutcome.OUT_UNKNOWN || prev.outcome == JumpOutcome.OUT_OVERSHOOT || prev.outcome == JumpOutcome.OUT_SHORT) &&
                    abs(prev.normalizedDistancePx - dNorm) <= 35f

                when {
                    effK > ANCHOR_K + 0.08f || config.kFactor > ANCHOR_K + 0.08f -> {
                        val pull = ((config.kFactor - ANCHOR_K) * 0.45f).coerceIn(0.055f, 0.14f)
                        newK -= pull
                        newB -= if (sameSpotRepeatOut) 8f else 4f
                        note = String.format(Locale.US, "Out-over recovery (-%.3f k)", pull)
                    }
                    effK < ANCHOR_K - 0.12f || config.kFactor < ANCHOR_K - 0.12f -> {
                        val pull = ((ANCHOR_K - config.kFactor) * 0.45f).coerceIn(0.055f, 0.14f)
                        newK += pull
                        newB += if (sameSpotRepeatOut) 8f else 4f
                        note = String.format(Locale.US, "Out-short recovery (+%.3f k)", pull)
                    }
                    sameSpotRepeatOut -> {
                        // Gentle perturbation toward anchor if repeated OUT happens near nominal slope
                        val nudge = if (config.kFactor >= ANCHOR_K) -0.05f else 0.05f
                        newK += nudge
                        note = String.format(Locale.US, "Repeat-out nudge (%.3f)", nudge)
                    }
                    else -> {
                        note = "Out guard (near nominal)"
                    }
                }
            }

            landingRatio != null && landingRatio < 0.72f -> {
                // Survived with r < 0.72 => camera already panned back before burst captured pawn
                note = String.format(Locale.US, "Pan-drifted (r=%.2f ignored)", landingRatio)
            }

            landingRatio != null && landingRatio in 0.72f..1.65f -> {
                val errorRatio = 1.0f - landingRatio
                if (abs(errorRatio) <= 0.045f) {
                    note = String.format(Locale.US, "Dead center (r=%.2f)", landingRatio)
                } else {
                    val safeRatio = landingRatio.coerceIn(0.74f, 1.40f)
                    val idealHoldMs = (holdMs.toFloat() / safeRatio)
                        .coerceIn(config.minPressMs.toFloat(), config.maxPressMs.toFloat())
                    val gain = if (outcome == JumpOutcome.SHORT || outcome == JumpOutcome.PAST_CENTER) 0.42f else 0.30f
                    val deltaMs = (idealHoldMs - holdMs.toFloat()) * gain

                    newK += (deltaMs * 0.85f) / dNorm.coerceAtLeast(150f)
                    newB += deltaMs * 0.15f
                    note = String.format(Locale.US, "LMS r=%.2f Δ=%.0fms", landingRatio, deltaMs)
                }
            }

            outcome == JumpOutcome.SHORT -> {
                newK += 0.040f
                newB += 6f
                note = "Short fallback (+k,+b)"
            }
        }

        // Damped 2-parameter OLS blend ONLY over clean landed jumps since the latest manual override
        val validForOls = !isFatalOut && (landingRatio == null || landingRatio >= 0.72f)
        if (validForOls) {
            val fitSamples = history.asSequence()
                .takeWhile { it.outcome != JumpOutcome.USER_SHORT && it.outcome != JumpOutcome.USER_LONG }
                .filter {
                    (it.outcome == JumpOutcome.CENTER || it.outcome == JumpOutcome.SHORT || it.outcome == JumpOutcome.PAST_CENTER) &&
                        it.landingRatio != null &&
                        it.landingRatio in 0.72f..1.40f &&
                        it.normalizedDistancePx >= 150f
                }
                .take(18)
                .toList()

            if (fitSamples.size >= 4) {
                val minD = fitSamples.minOf { it.normalizedDistancePx }
                val maxD = fitSamples.maxOf { it.normalizedDistancePx }
                if (maxD - minD >= 100f) {
                    val fitted = weightedLinearFit(fitSamples, config)
                    if (fitted != null) {
                        newK = newK * 0.86f + fitted.first * 0.14f
                        newB = newB * 0.86f + fitted.second * 0.14f
                        note += " + OLS"
                    }
                }
            }
        }

        val maxStep = if (allowLargeRecovery) 0.15f else MAX_AUTO_K_STEP
        val clampedStepK = (newK - config.kFactor).coerceIn(-maxStep, maxStep)
        newK = (((config.kFactor + clampedStepK).coerceIn(MIN_K, MAX_K)) * 1000f).roundToInt() / 1000f
        newB = ((newB.coerceIn(MIN_B, MAX_B)) * 10f).roundToInt() / 10f

        val updated = config.copy(
            formulaMode = if (config.formulaMode == FormulaMode.QUADRATIC) FormulaMode.QUADRATIC else FormulaMode.LINEAR,
            kFactor = newK,
            offsetMs = newB
        )
        return updated to note
    }

    fun applyManualFeedback(
        config: BotConfig,
        lastRecord: JumpTelemetryRecord?,
        isShort: Boolean
    ): Triple<BotConfig, JumpTelemetryRecord?, String> {
        val dNorm = lastRecord?.normalizedDistancePx?.takeIf { it >= 100f } ?: 500f
        val deltaHoldMs = if (isShort) 42f else -42f
        val newK = (((config.kFactor + (deltaHoldMs * 0.82f) / dNorm).coerceIn(MIN_K, MAX_K)) * 1000f).roundToInt() / 1000f
        val newB = (((config.offsetMs + deltaHoldMs * 0.18f).coerceIn(MIN_B, MAX_B)) * 10f).roundToInt() / 10f

        val updatedCfg = config.copy(kFactor = newK, offsetMs = newB)
        val outcome = if (isShort) JumpOutcome.USER_SHORT else JumpOutcome.USER_LONG
        // Clear stale sensor ratio on manually overridden records so it never pulls OLS the wrong way
        val updatedRec = lastRecord?.copy(
            outcome = outcome,
            landingRatio = null,
            kAfter = newK,
            bAfter = newB,
            note = "Manual HUD: ${if (isShort) "Too Short (+k)" else "Too Long (-k)"}"
        )
        val msg = String.format(
            Locale.US,
            "Trained [%s]: k %.2f→%.2f, b %.0f→%.0f",
            outcome.shortLabel,
            config.kFactor,
            newK,
            config.offsetMs,
            newB
        )
        return Triple(updatedCfg, updatedRec, msg)
    }

    private fun weightedLinearFit(
        samples: List<JumpTelemetryRecord>,
        config: BotConfig
    ): Pair<Float, Float>? {
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (rec in samples) {
            val r = (rec.landingRatio ?: continue).coerceIn(0.60f, 1.45f)
            val idealY = (rec.holdMs.toDouble() / r).coerceIn(config.minPressMs.toDouble(), config.maxPressMs.toDouble())
            val x = rec.normalizedDistancePx.toDouble()
            val w = if (rec.outcome == JumpOutcome.CENTER) 1.6 else 1.0
            sw += w
            sx += w * x
            sy += w * idealY
            sxx += w * x * x
            sxy += w * x * idealY
        }
        val denom = sw * sxx - sx * sx
        if (abs(denom) < 1e-3) return null
        val slope = ((sw * sxy - sx * sy) / denom).toFloat()
        val intercept = ((sy - slope * sx) / sw).toFloat()
        if (slope !in MIN_K..MAX_K || intercept !in MIN_B..MAX_B) return null
        return slope to intercept
    }

    fun formatTelemetryReport(
        config: BotConfig,
        records: List<JumpTelemetryRecord>,
        logs: List<BotLogEntry>,
        screenW: Int,
        screenH: Int
    ): String {
        val total = records.size
        val centerCount = records.count { it.outcome == JumpOutcome.CENTER }
        val shortCount = records.count { it.outcome == JumpOutcome.SHORT || it.outcome == JumpOutcome.OUT_SHORT || it.outcome == JumpOutcome.USER_SHORT }
        val farCount = records.count { it.outcome == JumpOutcome.PAST_CENTER || it.outcome == JumpOutcome.OUT_OVERSHOOT || it.outcome == JumpOutcome.USER_LONG }
        val validRatios = records.mapNotNull { it.landingRatio }
        val avgRatioStr = if (validRatios.isNotEmpty()) {
            String.format(Locale.US, "%.2f", validRatios.average())
        } else {
            "NA"
        }

        val sb = StringBuilder()
        sb.appendLine("=== JumpAssist Training Report ===")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}) | Screen: ${screenW}x${screenH}")
        sb.appendLine(
            String.format(
                Locale.US,
                "Config: mode=%s k=%.3f b=%.1f pawn=#%06X tol=%d bgDiff=%d roi=%.2f..%.2f cooldown=%dms autoTrain=%s",
                config.formulaMode.name,
                config.kFactor,
                config.offsetMs,
                config.pawnColorRgb and 0xFFFFFF,
                config.pawnColorTolerance,
                config.bgDiffThreshold,
                config.roiTopFraction,
                config.roiBottomFraction,
                config.jumpCooldownMs,
                config.autoTrainEnabled
            )
        )
        sb.appendLine("Summary: total=$total center=$centerCount short=$shortCount far=$farCount avgRatio=$avgRatioStr")

        if (records.isNotEmpty()) {
            sb.appendLine("--- Jump History (newest first, up to 35) ---")
            records.take(35).forEach { r ->
                val ratioStr = r.landingRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "NA"
                val blkTag = if (r.blockTopHex.isNotEmpty()) " blk=[${r.blockTopHex},${r.blockSideHex}]" else ""
                sb.appendLine(
                    String.format(
                        Locale.US,
                        "#%d %s %s D=%.0f(n=%.0f) hold=%dms r=%s P=(%.0f,%.0f,%dpx) B=(%.0f,%.0f,w=%.0f) k=%.3f->%.3f b=%.0f->%.0f rgb=[%s,%s,%s]%s %s",
                        r.id,
                        r.timestamp,
                        r.outcome.shortLabel,
                        r.rawDistancePx,
                        r.normalizedDistancePx,
                        r.holdMs,
                        ratioStr,
                        r.pawnX,
                        r.pawnY,
                        r.pawnPixels,
                        r.blockX,
                        r.blockY,
                        r.blockWidth,
                        r.kBefore,
                        r.kAfter,
                        r.bBefore,
                        r.bAfter,
                        r.skyTopHex,
                        r.skyMidHex,
                        r.pawnSampleHex,
                        blkTag,
                        r.note
                    )
                )
            }
        }

        if (logs.isNotEmpty()) {
            sb.appendLine("--- Recent Logs (up to 12) ---")
            logs.take(12).forEach { l ->
                sb.appendLine("[${l.timestamp}]${if (l.isError) " ERR:" else ""} ${l.message}")
            }
        }
        return sb.toString().trim()
    }
}
