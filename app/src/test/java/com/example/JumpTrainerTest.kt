package com.example

import com.example.model.BlockBounds
import com.example.model.BotConfig
import com.example.model.BotLogEntry
import com.example.model.JumpOutcome
import com.example.model.JumpTelemetryRecord
import com.example.model.PointF2D
import com.example.model.VisionAnalysisResult
import com.example.training.JumpTrainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpTrainerTest {

    private fun makeResult(
        pawnFound: Boolean = true,
        px: Float = 218f,
        py: Float = 954f,
        blockFound: Boolean = true,
        bx: Float = 548f,
        by: Float = 755f,
        dRaw: Float = 385f,
        dNorm: Float = 577f,
        holdMs: Long = 765L
    ): VisionAnalysisResult {
        return VisionAnalysisResult(
            imageWidth = 720,
            imageHeight = 1600,
            roiTopY = 448f,
            roiBottomY = 1152f,
            pawnFound = pawnFound,
            pawnCenterX = px,
            pawnBaseY = py,
            pawnPixelCount = if (pawnFound) 2600 else 0,
            blockFound = blockFound,
            blockBounds = if (blockFound) {
                BlockBounds(
                    apex = PointF2D(bx, by - 72f),
                    center = PointF2D(bx, by),
                    leftEdgeX = bx - 130f,
                    rightEdgeX = bx + 130f,
                    topY = by - 72f,
                    bottomY = by + 16f,
                    widthPx = 260f,
                    heightPx = 88f
                )
            } else null,
            rawDistancePx = dRaw,
            normalizedDistancePx = dNorm,
            computedDurationMs = holdMs,
            safeMarginMs = 150L,
            analysisTimeMs = 22L,
            statusMessage = "ok"
        )
    }

    @Test
    fun `computeLandingRatio measures short center and overshoot accurately`() {
        val pre = makeResult(px = 218f, py = 954f, bx = 548f, by = 755f, dRaw = 385.3f)

        val shortLand = makeResult(px = 482f, py = 795f) // 80% of jump vector
        val shortRatio = JumpTrainer.computeLandingRatio(pre, shortLand)
        assertNotNull(shortRatio)
        assertEquals(0.80f, shortRatio!!, 0.03f)

        val centerLand = makeResult(px = 548f, py = 755f)
        val centerRatio = JumpTrainer.computeLandingRatio(pre, centerLand)
        assertNotNull(centerRatio)
        assertEquals(1.00f, centerRatio!!, 0.02f)

        val overLand = makeResult(px = 630f, py = 706f) // ~125%
        val overRatio = JumpTrainer.computeLandingRatio(pre, overLand)
        assertNotNull(overRatio)
        assertEquals(1.25f, overRatio!!, 0.03f)

        // Orthogonal unrelated artifact should be rejected
        val bogusLand = makeResult(px = 218f, py = 500f)
        assertNull(JumpTrainer.computeLandingRatio(pre, bogusLand))
    }

    @Test
    fun `online update raises k on short jumps and lowers k on overshoots`() {
        val cfg = BotConfig(kFactor = 1.26f, offsetMs = 38f, autoTrainEnabled = true)

        val (raised, shortNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 577f,
            holdMs = 765L,
            outcome = JumpOutcome.SHORT,
            landingRatio = 0.80f,
            history = emptyList()
        )
        assertTrue("Short jump should increase kFactor ($shortNote)", raised.kFactor > cfg.kFactor)
        assertTrue("Short jump should increase offsetMs", raised.offsetMs > cfg.offsetMs)

        val (lowered, overNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 577f,
            holdMs = 765L,
            outcome = JumpOutcome.PAST_CENTER,
            landingRatio = 1.22f,
            history = emptyList()
        )
        assertTrue("Overshoot jump should lower kFactor ($overNote)", lowered.kFactor < cfg.kFactor)
        assertTrue("Overshoot jump should lower offsetMs", lowered.offsetMs < cfg.offsetMs)

        val (unchanged, centerNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 577f,
            holdMs = 765L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 0.99f,
            history = emptyList()
        )
        assertEquals("Dead center jump should keep k steady ($centerNote)", cfg.kFactor, unchanged.kFactor, 0.001f)
    }

    @Test
    fun `manual feedback and report formatting produce copyable diagnostics`() {
        val cfg = BotConfig()
        val rec = JumpTelemetryRecord(
            id = 1L,
            timestamp = "12:00:01",
            pawnX = 218f,
            pawnY = 954f,
            pawnPixels = 2600,
            blockX = 548f,
            blockY = 755f,
            blockWidth = 260f,
            blockHeight = 88f,
            rawDistancePx = 385f,
            normalizedDistancePx = 577f,
            holdMs = 765L,
            landingRatio = 0.85f,
            outcome = JumpOutcome.OUT_SHORT,
            kBefore = 1.26f,
            bBefore = 38f,
            kAfter = 1.32f,
            bAfter = 42f,
            skyTopHex = "#5E5C9D",
            skyMidHex = "#828ACB",
            pawnSampleHex = "#3E3353",
            note = "Test"
        )
        val (updatedCfg, updatedRec, msg) = JumpTrainer.applyManualFeedback(cfg, rec, isShort = true)
        assertTrue("Manual short feedback increases k", updatedCfg.kFactor > cfg.kFactor)
        assertEquals(JumpOutcome.USER_SHORT, updatedRec?.outcome)
        assertTrue(msg.contains("USR_SHORT"))

        val report = JumpTrainer.formatTelemetryReport(
            config = updatedCfg,
            records = listOf(updatedRec!!),
            logs = listOf(BotLogEntry(1L, "12:00:02", "Self-Train active", isError = false)),
            screenW = 720,
            screenH = 1600
        )
        assertTrue(report.contains("=== JumpAssist Training Report ==="))
        assertTrue(report.contains("720x1600"))
        assertTrue(report.contains("#5E5C9D"))
        assertTrue(report.contains("D=385(n=577)"))
        assertTrue(report.contains("Self-Train active"))
    }

    @Test
    fun `fatal OUT_OVERSHOOT with near-1 edge ratio still lowers k and skips OLS undo`() {
        val cfg = BotConfig(kFactor = 1.56f, offsetMs = 10f, autoTrainEnabled = true)
        val staleHistory = (1..5).map { idx ->
            JumpTelemetryRecord(
                id = idx.toLong(),
                timestamp = "12:00:0$idx",
                pawnX = 220f,
                pawnY = 950f,
                pawnPixels = 2700,
                blockX = 500f,
                blockY = 740f,
                blockWidth = 236f,
                blockHeight = 80f,
                rawDistancePx = 220f + idx * 40f,
                normalizedDistancePx = 330f + idx * 60f,
                holdMs = (530 + idx * 95).toLong(),
                landingRatio = 0.90f, // biased upward
                outcome = JumpOutcome.CENTER,
                kBefore = 1.56f,
                bBefore = 10f,
                kAfter = 1.56f,
                bAfter = 10f
            )
        }

        val (afterOut, note) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 410f,
            holdMs = 591L,
            outcome = JumpOutcome.OUT_OVERSHOOT,
            landingRatio = 1.04f,
            history = staleHistory
        )

        assertTrue("OUT_OVERSHOOT must reduce k even if pre-fall ratio was 1.04 ($note)", afterOut.kFactor < cfg.kFactor)
        assertTrue("OUT_OVERSHOOT must not label Dead center ($note)", !note.contains("Dead center"))
        assertTrue("OUT_OVERSHOOT must skip stale OLS blend ($note)", !note.contains("OLS"))
    }

    @Test
    fun `manual correction clears stale landingRatio and blocks older history from undoing fix`() {
        val cfg = BotConfig(kFactor = 1.48f, offsetMs = 2f, autoTrainEnabled = true)
        val misleadingRecord = JumpTelemetryRecord(
            id = 10L,
            timestamp = "16:21:46",
            pawnX = 247f,
            pawnY = 932f,
            pawnPixels = 3763,
            blockX = 373f,
            blockY = 754f,
            blockWidth = 236f,
            blockHeight = 80f,
            rawDistancePx = 219f,
            normalizedDistancePx = 328f,
            holdMs = 473L,
            landingRatio = 1.05f,
            outcome = JumpOutcome.CENTER,
            kBefore = 1.48f,
            bBefore = 2f,
            kAfter = 1.48f,
            bAfter = 2f
        )

        val (manualCfg, manualRec, _) = JumpTrainer.applyManualFeedback(cfg, misleadingRecord, isShort = true)
        assertNull("Manual correction must clear stale sensor landingRatio", manualRec?.landingRatio)
        assertTrue(manualCfg.kFactor > cfg.kFactor)

        val olderLowHistory = listOf(manualRec!!) + (1..5).map { idx ->
            misleadingRecord.copy(
                id = idx.toLong(),
                normalizedDistancePx = 300f + idx * 70f,
                holdMs = (400 + idx * 80).toLong(),
                landingRatio = 1.15f,
                outcome = JumpOutcome.CENTER
            )
        }

        val (afterCenter, note) = JumpTrainer.applyOnlineUpdate(
            config = manualCfg,
            dNorm = 500f,
            holdMs = 740L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 1.00f,
            history = olderLowHistory
        )
        assertEquals("Older pre-manual records must not drag k back down via OLS ($note)", manualCfg.kFactor, afterCenter.kFactor, 0.001f)
    }
}
