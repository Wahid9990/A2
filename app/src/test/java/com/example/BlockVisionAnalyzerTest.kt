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

    @Test
    fun `ignores radial center sky glow #816ACF on #7D65C1 sky and locks onto close wide block`() {
        val config = BotConfig(bgDiffThreshold = 38, roiTopFraction = 0.28f, roiBottomFraction = 0.72f)
        val w = 720
        val h = 1600
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)

        // Draw #4F448A -> #7D65C1 gradient sky matching real vivo telemetry
        val skyPaint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 448f, 0f, 1152f,
                intArrayOf(android.graphics.Color.rgb(0x4F, 0x44, 0x8A), android.graphics.Color.rgb(0x7D, 0x65, 0xC1)),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), skyPaint)

        // Draw smooth strong radial center sky vignette (#8F74DF center over #4F448A -> #7D65C1 sky so center vs edge distSq > 5000 exceeds Pass 1!)
        val glowPaint = android.graphics.Paint().apply {
            shader = android.graphics.RadialGradient(
                360f, 690f, 240f,
                intArrayOf(android.graphics.Color.argb(245, 0x8F, 0x74, 0xDF), android.graphics.Color.argb(0, 0x5B, 0x48, 0x94)),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(360f, 690f, 240f, glowPaint)

        // Draw pawn at (218, 954) so sky glow at (360, 690) is right in the false target zone
        val pawnPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        }
        val px = 218f
        val py = 954f
        val bodyPath = android.graphics.Path().apply {
            moveTo(px - 14f, py); lineTo(px + 14f, py); lineTo(px + 9f, py - 46f); lineTo(px - 9f, py - 46f); close()
        }
        canvas.drawPath(bodyPath, pawnPaint)
        canvas.drawOval(px - 16f, py - 10f, px + 16f, py + 4f, pawnPaint)
        val collarPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(210, 175, 98)
        }
        canvas.drawOval(px - 12f, py - 56f, px + 12f, py - 46f, collarPaint)
        canvas.drawCircle(px, py - 70f, 12f, pawnPaint)

        // Verify empty sky glow frame does NOT falsely lock onto (360, 690) even in Pass 1
        val emptySkyResult = BlockVisionAnalyzer.analyze(bmp, config)
        assertTrue("Pawn should be detected on radial glow frame", emptySkyResult.pawnFound)
        assertTrue("Smooth radial sky glow #8F74DF must NOT be detected as a block, got ${emptySkyResult.blockBounds}", !emptySkyResult.blockFound)

        // Now paint a wide (308px) & vertically close (dy = 82px) real chest block at (500, 872)
        val bx = 500f
        val by = 872f
        val hw = 154f
        val hh = 76f
        val depth = 64f
        val blockPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val topPath = android.graphics.Path().apply {
            moveTo(bx, by - hh); lineTo(bx + hw, by); lineTo(bx, by + hh); lineTo(bx - hw, by); close()
        }
        val leftPath = android.graphics.Path().apply {
            moveTo(bx - hw, by); lineTo(bx, by + hh); lineTo(bx, by + hh + depth); lineTo(bx - hw, by + depth); close()
        }
        val rightPath = android.graphics.Path().apply {
            moveTo(bx + hw, by); lineTo(bx, by + hh); lineTo(bx, by + hh + depth); lineTo(bx + hw, by + depth); close()
        }
        blockPaint.color = android.graphics.Color.rgb(185, 118, 56); canvas.drawPath(leftPath, blockPaint)
        blockPaint.color = android.graphics.Color.rgb(155, 92, 42); canvas.drawPath(rightPath, blockPaint)
        blockPaint.color = android.graphics.Color.rgb(235, 194, 88); canvas.drawPath(topPath, blockPaint)

        val closeWideResult = BlockVisionAnalyzer.analyze(bmp, config)
        bmp.recycle()

        assertTrue("Close wide chest block (w=308, dy=82) should be detected", closeWideResult.blockFound)
        val lockedCenter = closeWideResult.blockBounds?.center
        assertTrue(
            "Analyzer should lock onto real block near (500, 872) rather than sky glow (360, 690); got $lockedCenter",
            lockedCenter != null && kotlin.math.abs(lockedCenter.x - 500f) < 35f && kotlin.math.abs(lockedCenter.y - 872f) < 35f
        )
    }

    @Test
    fun `detects newly appeared upper block centroid between touchdown and settled frames`() {
        val config = BotConfig(roiTopFraction = 0.28f, roiBottomFraction = 0.72f)
        val bmpPre = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1600,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 220f,
            distanceFactor = 0.72f
        )
        val preRes = BlockVisionAnalyzer.analyze(bmpPre, config)
        assertTrue("Pawn should lock on touchdown frame", preRes.pawnFound)

        // Wipe the synthetic target block in bmpTouchdown so it simulates the frame BEFORE the new block drops in
        val bmpTouchdown = bmpPre.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val wipeCanvas = android.graphics.Canvas(bmpTouchdown)
        val wipePaint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(112, 118, 178) }
        val bx = preRes.blockBounds!!.center.x
        val by = preRes.blockBounds!!.center.y
        wipeCanvas.drawRect(bx - 130f, by - 90f, bx + 130f, by + 130f, wipePaint)

        val snap = BlockVisionAnalyzer.captureUpperRoiSnapshot(
            bitmap = bmpTouchdown,
            roiTopY = preRes.roiTopY,
            roiBottomY = preRes.roiBottomY,
            pawnX = preRes.pawnCenterX,
            pawnY = preRes.pawnBaseY
        )

        // Compare touchdown (without block) against bmpPre (with newly dropped block)
        val hint = BlockVisionAnalyzer.detectNewlyAppearedHint(
            prevSnapshot = snap,
            currentBitmap = bmpPre,
            currPawnX = preRes.pawnCenterX,
            currPawnY = preRes.pawnBaseY
        )
        val hintedRes = BlockVisionAnalyzer.analyze(bmpPre, config, preferredTargetHint = hint)
        bmpTouchdown.recycle()
        bmpPre.recycle()

        assertTrue("Newly appeared hint should point near ($bx, $by), got $hint", hint != null && kotlin.math.abs(hint.x - bx) < 45f && kotlin.math.abs(hint.y - by) < 85f)
        assertTrue("Hinted analysis should lock cleanly onto new block", hintedRes.blockFound)
    }
}
