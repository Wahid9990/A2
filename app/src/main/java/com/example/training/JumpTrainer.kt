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
    private const val ANCHOR_K = 1.38f
    private const val MAX_AUTO_K_STEP = 0.055f
    private const val MAX_CONTEXT_BIAS_MS = 140f
    private const val MAX_MISTAKE_GUARD_MS = 120f
    private const val MAX_STORED_BIASES = 36

    fun distBucket(dNorm: Float): Int = ((dNorm / 50f).roundToInt() * 50).coerceIn(100, 1200)

    fun profileKey(targetProfile: String): String = "BLK:${targetProfile.ifBlank { "MEDIUM" }}"

    fun signatureKey(targetProfile: String, colorGroup: String): String =
        if (colorGroup.isNotBlank()) "SIG:${targetProfile.ifBlank { "MEDIUM" }}_$colorGroup" else ""

    fun stanceKey(pawnStance: String): String =
        if (pawnStance.isNotBlank() && pawnStance != "UNKNOWN") "STANCE:$pawnStance" else ""

    fun mistakeKey(targetProfile: String, pawnStance: String, dNorm: Float): String =
        "MIST:${targetProfile.ifBlank { "MEDIUM" }}:${pawnStance.ifBlank { "CENTER" }}:d${distBucket(dNorm)}"

    /**
     * Computes learned millisecond adjustment for a specific target block profile/color,
     * pawn source stance (CENTER vs SIDE_FRONT/SIDE_BACK/SIDE_EDGE), and exact spot mistake guard.
     */
    fun computeContextAdjustmentMs(
        config: BotConfig,
        normalizedDistancePx: Float,
        targetBlockProfile: String,
        targetColorGroup: String,
        pawnStance: String
    ): Long {
        val biases = config.adaptiveBiasesMs
        if (biases.isEmpty()) return 0L

        val blkBias = biases[profileKey(targetBlockProfile)] ?: 0f
        val sigKey = signatureKey(targetBlockProfile, targetColorGroup)
        val sigBias = if (sigKey.isNotEmpty()) (biases[sigKey] ?: 0f) * 0.85f else 0f
        val stKey = stanceKey(pawnStance)
        val stanceBias = if (stKey.isNotEmpty()) (biases[stKey] ?: 0f) else 0f
        val mistBias = biases[mistakeKey(targetBlockProfile, pawnStance, normalizedDistancePx)] ?: 0f

        return (blkBias + sigBias + stanceBias + mistBias)
            .roundToInt()
            .toLong()
            .coerceIn(-MAX_CONTEXT_BIAS_MS.toLong(), MAX_CONTEXT_BIAS_MS.toLong())
    }

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
     * Context-aware online self-training:
     * - Standard CENTER + MEDIUM jumps refine global (kFactor, offsetMs) + clean OLS blend.
     * - Off-center stances (SIDE_FRONT/BACK/EDGE) and special target block shapes (NARROW/WIDE/color)
     *   learn dedicated millisecond biases so specialty power differences don't corrupt base calibration.
     * - Fatal misses record explicit MIST guards so the same failed duration is never blindly repeated.
     */
    fun applyOnlineUpdate(
        config: BotConfig,
        dNorm: Float,
        holdMs: Long,
        outcome: JumpOutcome,
        landingRatio: Float?,
        history: List<JumpTelemetryRecord>,
        targetProfile: String = "MEDIUM",
        targetColorGroup: String = "",
        pawnStance: String = "CENTER"
    ): Pair<BotConfig, String> {
        if (!config.autoTrainEnabled || dNorm < 80f) {
            return config to "Self-train skipped"
        }

        var newK = config.kFactor
        var newB = config.offsetMs
        val newBiases = LinkedHashMap(config.adaptiveBiasesMs)
        val isFatalOut = outcome == JumpOutcome.OUT_SHORT ||
            outcome == JumpOutcome.OUT_OVERSHOOT ||
            outcome == JumpOutcome.OUT_UNKNOWN
        val isMiniBlock = targetProfile == "MINI"
        val isSpecialtyContext = targetProfile != "MEDIUM" ||
            (pawnStance != "CENTER" && pawnStance != "UNKNOWN")
        val globalShare = when {
            isMiniBlock -> 0.18f
            isSpecialtyContext -> 0.35f
            else -> 1.0f
        }
        var allowLargeRecovery = false
        var note = "Keep"
        var learnedErrorMs = 0f

        val pKey = profileKey(targetProfile)
        val sKey = signatureKey(targetProfile, targetColorGroup)
        val stKey = stanceKey(pawnStance)
        val mKey = mistakeKey(targetProfile, pawnStance, dNorm)

        when {
            outcome == JumpOutcome.OUT_SHORT -> {
                if (landingRatio != null && landingRatio in 0.45f..0.95f) {
                    val safeRatio = landingRatio.coerceIn(0.68f, 0.92f)
                    val idealHoldMs = (holdMs.toFloat() / safeRatio)
                        .coerceIn(config.minPressMs.toFloat(), config.maxPressMs.toFloat())
                    val deltaMs = ((idealHoldMs - holdMs.toFloat()) * 0.50f).coerceAtLeast(22f)
                    learnedErrorMs = deltaMs
                    newK += ((deltaMs * globalShare) * 0.85f) / dNorm.coerceAtLeast(150f)
                    newB += (deltaMs * globalShare) * 0.15f
                    note = String.format(Locale.US, "Out-short LMS r=%.2f +%.0fms", landingRatio, deltaMs)
                } else {
                    learnedErrorMs = 36f
                    newK += 0.045f * globalShare
                    newB += 6f * globalShare
                    note = "Out-short guard (+k,+b)"
                }
            }

            outcome == JumpOutcome.OUT_OVERSHOOT -> {
                allowLargeRecovery = true
                if (landingRatio != null && landingRatio in 1.06f..1.65f) {
                    val safeRatio = landingRatio.coerceIn(1.07f, 1.45f)
                    val idealHoldMs = (holdMs.toFloat() / safeRatio)
                        .coerceIn(config.minPressMs.toFloat(), config.maxPressMs.toFloat())
                    val deltaMs = ((idealHoldMs - holdMs.toFloat()) * 0.52f).coerceAtMost(-24f)
                    learnedErrorMs = deltaMs
                    newK += ((deltaMs * globalShare) * 0.85f) / dNorm.coerceAtLeast(150f)
                    newB += (deltaMs * globalShare) * 0.15f
                    note = String.format(Locale.US, "Out-far LMS r=%.2f %.0fms", landingRatio, deltaMs)
                } else {
                    learnedErrorMs = -42f
                    newK -= 0.055f * globalShare
                    newB -= 6f * globalShare
                    note = "Out-far guard (-k,-b)"
                }
            }

            outcome == JumpOutcome.OUT_UNKNOWN -> {
                allowLargeRecovery = true
                val effK = holdMs.toFloat() / dNorm.coerceAtLeast(150f)
                val prevSameSpot = history.firstOrNull {
                    (it.outcome == JumpOutcome.OUT_UNKNOWN || it.outcome == JumpOutcome.OUT_OVERSHOOT || it.outcome == JumpOutcome.OUT_SHORT) &&
                        it.targetProfile == targetProfile &&
                        abs(it.normalizedDistancePx - dNorm) <= 45f
                }
                val existingMist = newBiases[mKey] ?: 0f

                when {
                    effK > ANCHOR_K + 0.08f || config.kFactor > ANCHOR_K + 0.08f -> {
                        val pull = ((config.kFactor - ANCHOR_K) * 0.45f).coerceIn(0.055f, 0.14f)
                        newK -= pull * globalShare
                        newB -= if (prevSameSpot != null) 8f * globalShare else 4f * globalShare
                        learnedErrorMs = -40f
                        note = String.format(Locale.US, "Out-over recovery (-%.3f k)", pull)
                    }
                    effK < ANCHOR_K - 0.12f || config.kFactor < ANCHOR_K - 0.12f -> {
                        val pull = ((ANCHOR_K - config.kFactor) * 0.45f).coerceIn(0.055f, 0.14f)
                        newK += pull * globalShare
                        newB += if (prevSameSpot != null) 8f * globalShare else 4f * globalShare
                        learnedErrorMs = 40f
                        note = String.format(Locale.US, "Out-short recovery (+%.3f k)", pull)
                    }
                    else -> {
                        // Never repeat the exact same failed hold: pick direction from stance / prior attempt
                        val guardDelta = when {
                            existingMist <= -70f -> 48f // previous downward guards still missed, flip to avoid trap
                            existingMist >= 70f -> -48f
                            pawnStance == "SIDE_BACK" -> 38f
                            pawnStance == "SIDE_FRONT" -> -38f
                            prevSameSpot != null && prevSameSpot.holdMs >= holdMs -> -36f
                            else -> -36f
                        }
                        learnedErrorMs = guardDelta
                        newK += (guardDelta * 0.25f) / dNorm.coerceAtLeast(150f)
                        note = String.format(Locale.US, "Mistake guard (%+.0fms on %s/%s)", guardDelta, targetProfile, pawnStance)
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
                    // Reinforce winning mistake guard into permanent block/stance bias cleanly
                    val wonMist = newBiases[mKey]
                    if (wonMist != null && abs(wonMist) >= 4f) {
                        newBiases[pKey] = ((newBiases[pKey] ?: 0f) + wonMist * 0.20f).coerceIn(-90f, 90f)
                        newBiases[mKey] = (wonMist * 0.80f)
                    }
                } else {
                    val safeRatio = landingRatio.coerceIn(0.74f, 1.40f)
                    val idealHoldMs = (holdMs.toFloat() / safeRatio)
                        .coerceIn(config.minPressMs.toFloat(), config.maxPressMs.toFloat())
                    val gain = if (outcome == JumpOutcome.SHORT || outcome == JumpOutcome.PAST_CENTER) 0.44f else 0.30f
                    val deltaMs = (idealHoldMs - holdMs.toFloat()) * gain
                    learnedErrorMs = deltaMs

                    newK += ((deltaMs * globalShare) * 0.85f) / dNorm.coerceAtLeast(150f)
                    newB += (deltaMs * globalShare) * 0.15f
                    note = String.format(Locale.US, "LMS r=%.2f Δ=%.0fms", landingRatio, deltaMs)
                }
            }

            outcome == JumpOutcome.SHORT -> {
                learnedErrorMs = 30f
                newK += 0.040f * globalShare
                newB += 6f * globalShare
                note = "Short fallback (+k,+b)"
            }
        }

        // Propagate contextual bias to Block Shape, Block Color Signature, Pawn Stance, and Spot Mistake Guard
        if (abs(learnedErrorMs) >= 3f) {
            val ctxWeight = when {
                isMiniBlock -> 0.90f
                isSpecialtyContext -> 0.72f
                else -> 0.28f
            }
            val ctxDelta = learnedErrorMs * ctxWeight

            val blkShare = when {
                isMiniBlock && (stKey.isEmpty() || pawnStance == "CENTER") -> 0.78f
                isMiniBlock -> 0.55f
                stKey.isNotEmpty() && pawnStance != "CENTER" -> 0.45f
                else -> 0.65f
            }
            val blkCap = if (isMiniBlock) 110f else 90f
            val curBlk = newBiases[pKey] ?: 0f
            newBiases[pKey] = (curBlk + ctxDelta * blkShare).coerceIn(-blkCap, blkCap)

            if (sKey.isNotEmpty()) {
                val curSig = newBiases[sKey] ?: 0f
                newBiases[sKey] = (curSig + ctxDelta * 0.25f).coerceIn(-75f, 75f)
            }

            if (stKey.isNotEmpty() && pawnStance != "CENTER") {
                val curSt = newBiases[stKey] ?: 0f
                newBiases[stKey] = (curSt + ctxDelta * 0.35f).coerceIn(-75f, 75f)
                if (!note.contains(pawnStance)) {
                    note += " [$pawnStance]"
                }
            }

            if (isFatalOut || outcome == JumpOutcome.SHORT || outcome == JumpOutcome.PAST_CENTER) {
                val mistDelta = if (isFatalOut) learnedErrorMs.coerceIn(-65f, 65f) else (learnedErrorMs * 0.45f)
                val curMist = newBiases[mKey] ?: 0f
                newBiases[mKey] = (curMist + mistDelta).coerceIn(-MAX_MISTAKE_GUARD_MS, MAX_MISTAKE_GUARD_MS)
            }
        }

        // Damped 2-parameter OLS blend ONLY over clean STANDARD (CENTER + MEDIUM) jumps
        val validForOls = !isFatalOut && !isSpecialtyContext && (landingRatio == null || landingRatio >= 0.72f)
        if (validForOls) {
            val fitSamples = history.asSequence()
                .takeWhile { it.outcome != JumpOutcome.USER_SHORT && it.outcome != JumpOutcome.USER_LONG }
                .filter {
                    (it.outcome == JumpOutcome.CENTER || it.outcome == JumpOutcome.SHORT || it.outcome == JumpOutcome.PAST_CENTER) &&
                        it.landingRatio != null &&
                        it.landingRatio in 0.72f..1.40f &&
                        it.normalizedDistancePx >= 150f &&
                        it.targetProfile == "MEDIUM" &&
                        (it.pawnStance == "CENTER" || it.pawnStance == "UNKNOWN")
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

        val cleanedBiases = compactBiases(newBiases)

        val updated = config.copy(
            formulaMode = if (config.formulaMode == FormulaMode.QUADRATIC) FormulaMode.QUADRATIC else FormulaMode.LINEAR,
            kFactor = newK,
            offsetMs = newB,
            adaptiveBiasesMs = cleanedBiases
        )
        return updated to note
    }

    private fun compactBiases(source: Map<String, Float>): Map<String, Float> {
        val rounded = LinkedHashMap<String, Float>()
        source.forEach { (k, v) ->
            val clean = (v * 10f).roundToInt() / 10f
            if (abs(clean) >= 1.5f) {
                rounded[k] = clean
            }
        }
        if (rounded.size <= MAX_STORED_BIASES) return rounded
        // Keep all BLK: and STANCE: entries; trim oldest MIST: / SIG: entries first
        val kept = LinkedHashMap<String, Float>()
        val core = rounded.entries.filter { it.key.startsWith("BLK:") || it.key.startsWith("STANCE:") }
        val extra = rounded.entries.filterNot { it.key.startsWith("BLK:") || it.key.startsWith("STANCE:") }
        core.forEach { kept[it.key] = it.value }
        val budget = (MAX_STORED_BIASES - kept.size).coerceAtLeast(0)
        extra.takeLast(budget).forEach { kept[it.key] = it.value }
        return kept
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

        val updatedBiases = LinkedHashMap(config.adaptiveBiasesMs)
        if (lastRecord != null) {
            val ctxShift = deltaHoldMs * if (lastRecord.targetProfile == "MINI") 0.75f else 0.55f
            val pKey = profileKey(lastRecord.targetProfile)
            val blkCap = if (lastRecord.targetProfile == "MINI") 110f else 90f
            updatedBiases[pKey] = ((updatedBiases[pKey] ?: 0f) + ctxShift * 0.55f).coerceIn(-blkCap, blkCap)
            val sKey = signatureKey(lastRecord.targetProfile, lastRecord.targetColorGroup)
            if (sKey.isNotEmpty()) {
                updatedBiases[sKey] = ((updatedBiases[sKey] ?: 0f) + ctxShift * 0.35f).coerceIn(-75f, 75f)
            }
            val stKey = stanceKey(lastRecord.pawnStance)
            if (stKey.isNotEmpty() && lastRecord.pawnStance != "CENTER") {
                updatedBiases[stKey] = ((updatedBiases[stKey] ?: 0f) + ctxShift * 0.45f).coerceIn(-75f, 75f)
            }
            val mKey = mistakeKey(lastRecord.targetProfile, lastRecord.pawnStance, dNorm)
            updatedBiases[mKey] = ((updatedBiases[mKey] ?: 0f) + ctxShift).coerceIn(-MAX_MISTAKE_GUARD_MS, MAX_MISTAKE_GUARD_MS)
        }

        val updatedCfg = config.copy(
            kFactor = newK,
            offsetMs = newB,
            adaptiveBiasesMs = compactBiases(updatedBiases)
        )
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
                "Config: mode=%s k=%.3f b=%.1f pawn=#%06X tol=%d bgDiff=%d roi=%.2f..%.2f cooldown=%dms autoTrain=%s humanTiming=%s",
                config.formulaMode.name,
                config.kFactor,
                config.offsetMs,
                config.pawnColorRgb and 0xFFFFFF,
                config.pawnColorTolerance,
                config.bgDiffThreshold,
                config.roiTopFraction,
                config.roiBottomFraction,
                config.jumpCooldownMs,
                config.autoTrainEnabled,
                config.humanizeTiming
            )
        )
        if (config.adaptiveBiasesMs.isNotEmpty()) {
            val biasSummary = config.adaptiveBiasesMs.entries.joinToString(" ") { (k, v) ->
                String.format(Locale.US, "%s=%+.0fms", k, v)
            }
            sb.appendLine("AdaptiveBiases: $biasSummary")
        }
        sb.appendLine("Summary: total=$total center=$centerCount short=$shortCount far=$farCount avgRatio=$avgRatioStr")

        if (records.isNotEmpty()) {
            sb.appendLine("--- Jump History (newest first, up to 35) ---")
            records.take(35).forEach { r ->
                val ratioStr = r.landingRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "NA"
                val blkTag = if (r.blockTopHex.isNotEmpty()) " blk=[${r.blockTopHex},${r.blockSideHex}]" else ""
                val biasTag = if (r.appliedBiasMs != 0L) String.format(Locale.US, "(%+d)", r.appliedBiasMs) else ""
                val stanceTag = String.format(Locale.US, "S=%s(%.2f,sw=%.0f)", r.pawnStance, r.stanceOffsetFrac, r.sourceWidthPx)
                val targetTag = if (r.targetColorGroup.isNotEmpty()) "${r.targetProfile}:${r.targetColorGroup}" else r.targetProfile
                sb.appendLine(
                    String.format(
                        Locale.US,
                        "#%d %s %s D=%.0f(n=%.0f) hold=%dms%s r=%s P=(%.0f,%.0f,%dpx) %s B=(%.0f,%.0f,w=%.0f,%s) k=%.3f->%.3f b=%.0f->%.0f rgb=[%s,%s,%s]%s %s",
                        r.id,
                        r.timestamp,
                        r.outcome.shortLabel,
                        r.rawDistancePx,
                        r.normalizedDistancePx,
                        r.holdMs,
                        biasTag,
                        ratioStr,
                        r.pawnX,
                        r.pawnY,
                        r.pawnPixels,
                        stanceTag,
                        r.blockX,
                        r.blockY,
                        r.blockWidth,
                        targetTag,
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
