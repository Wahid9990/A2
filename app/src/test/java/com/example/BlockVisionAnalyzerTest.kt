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
}
