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
}
