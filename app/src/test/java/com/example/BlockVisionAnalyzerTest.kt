package com.example

import com.example.model.BotConfig
import com.example.model.FormulaMode
import com.example.vision.BlockVisionAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BlockVisionAnalyzerTest {

    @Test
    fun `detects pawn and next block accurately on synthetic frame`() {
        val config = BotConfig(
            formulaMode = FormulaMode.LINEAR,
            kFactor = 1.40f,
            offsetMs = 20f
        )
        val bitmap = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1280,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 170f,
            distanceFactor = 0.70f
        )

        val result = BlockVisionAnalyzer.analyze(bitmap, config)

        assertTrue("Pawn should be detected", result.pawnFound)
        assertTrue("Target block should be detected", result.blockFound)
        assertTrue("Measured distance should be > 150px", result.rawDistancePx > 150f)
        val blockWidth = result.blockBounds?.widthPx ?: 0f
        assertTrue("Block width should be near 170px, got $blockWidth", blockWidth in 140f..190f)
        assertTrue("Duration should be within bounds", result.computedDurationMs in config.minPressMs..config.maxPressMs)
    }

    @Test
    fun `detects half-transparent top block with colored base`() {
        val config = BotConfig()
        val bitmap = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1280,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 180f,
            distanceFactor = 0.72f,
            halfTransparentTarget = true
        )

        val result = BlockVisionAnalyzer.analyze(bitmap, config)
        println("HALF TRANSPARENT => $result")
        assertTrue("Pawn should be detected", result.pawnFound)
        assertTrue("Half-transparent block should be detected", result.blockFound)
        val width = result.blockBounds?.widthPx ?: 0f
        assertTrue("Half-transparent block width should be >= 140px, got $width", width >= 140f)
    }

    @Test
    fun `linear and quadratic duration formulas respect bounds`() {
        val linearCfg = BotConfig(
            formulaMode = FormulaMode.LINEAR,
            kFactor = 1.5f,
            offsetMs = 25f,
            minPressMs = 90L,
            maxPressMs = 1500L
        )
        assertEquals(325L, BlockVisionAnalyzer.computeDurationMs(200f, linearCfg))
        assertEquals(90L, BlockVisionAnalyzer.computeDurationMs(10f, linearCfg))
        assertEquals(1500L, BlockVisionAnalyzer.computeDurationMs(1500f, linearCfg))

        val quadCfg = BotConfig(
            formulaMode = FormulaMode.QUADRATIC,
            quadFactor = 0.001f,
            kFactor = 1.0f,
            offsetMs = 20f
        )
        // 0.001 * 200^2 + 1.0 * 200 + 20 = 40 + 200 + 20 = 260ms
        assertEquals(260L, BlockVisionAnalyzer.computeDurationMs(200f, quadCfg))
    }

    @Test
    fun `lighter pawn head with visible collar and cone does not falsely project baseY downward`() {
        val config = BotConfig()
        val fullBmp = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1280,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 220f,
            distanceFactor = 0.72f
        )
        // Repaint only the sphere head lighter (#625B84) while keeping the collar and cone intact
        val painted = fullBmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        fullBmp.recycle()
        val canvas = android.graphics.Canvas(painted)
        val lightHeadPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(98, 91, 132)
        }
        val curCx = 720f * 0.28f
        val curCy = 1280f * 0.63f
        canvas.drawCircle(curCx, curCy - 72f, 13f, lightHeadPaint)

        val result = BlockVisionAnalyzer.analyze(painted, config)
        painted.recycle()

        assertTrue("Pawn should still lock via cone and golden collar", result.pawnFound)
        assertTrue("Target block should still be found", result.blockFound)
        // Standing foot is around curCy - 6 (~800px), not projected down by +60px into block side
        assertTrue(
            "pawnBaseY should stay near pawn foot (~790..815), not falsely projected downward; got ${result.pawnBaseY}",
            result.pawnBaseY in 780f..820f
        )
    }

    @Test
    fun `detects low-contrast background-matching lavender block on purple sky`() {
        val config = BotConfig(bgDiffThreshold = 38)
        val bmp = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1280,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 210f,
            distanceFactor = 0.68f
        )
        // Paint over the bright yellow block with a subtle slate-purple block very close to the sky gradient (~#7A7DB2)
        val painted = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        bmp.recycle()
        val canvas = android.graphics.Canvas(painted)

        // Wipe the synthetic sky behind the target region cleanly first
        val bgPaint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, 1280f,
                intArrayOf(android.graphics.Color.rgb(94, 92, 157), android.graphics.Color.rgb(141, 148, 201)),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(300f, 450f, 710f, 750f, bgPaint)

        val curCx = 720f * 0.28f
        val curCy = 1280f * 0.63f
        val cx = curCx + 720f * 0.48f * 0.68f
        val cy = curCy - 1280f * 0.22f * 0.68f
        val hw = 105f
        val hh = 54f
        val depth = 82f

        val topPath = android.graphics.Path().apply {
            moveTo(cx, cy - hh); lineTo(cx + hw, cy); lineTo(cx, cy + hh); lineTo(cx - hw, cy); close()
        }
        val leftPath = android.graphics.Path().apply {
            moveTo(cx - hw, cy); lineTo(cx, cy + hh); lineTo(cx, cy + hh + depth); lineTo(cx - hw, cy + depth); close()
        }
        val rightPath = android.graphics.Path().apply {
            moveTo(cx + hw, cy); lineTo(cx, cy + hh); lineTo(cx, cy + hh + depth); lineTo(cx + hw, cy + depth); close()
        }
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Only ~12-18 RGB units away from the purple sky behind it!
        p.color = android.graphics.Color.rgb(138, 144, 198); canvas.drawPath(topPath, p)
        p.color = android.graphics.Color.rgb(112, 118, 178); canvas.drawPath(leftPath, p)
        p.color = android.graphics.Color.rgb(96, 102, 165); canvas.drawPath(rightPath, p)

        val result = BlockVisionAnalyzer.analyze(painted, config)
        val diag = BlockVisionAnalyzer.sampleFrameDiagnostics(
            bitmap = painted,
            roiTopY = result.roiTopY,
            roiBottomY = result.roiBottomY,
            pawnX = result.pawnCenterX,
            pawnY = result.pawnBaseY,
            blockX = result.blockBounds?.center?.x ?: 0f,
            blockY = result.blockBounds?.center?.y ?: 0f,
            blockBottomY = result.blockBounds?.bottomY ?: 0f
        )
        painted.recycle()

        assertTrue("Pawn should still lock", result.pawnFound)
        assertTrue("Low-contrast background-matching block should be detected: ${result.statusMessage}", result.blockFound)
        assertTrue("Measured block width should be >= 160px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) >= 160f)
        assertTrue("Block top color telemetry should be sampled", diag.blockTopHex.startsWith("#"))
    }
}
