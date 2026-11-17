package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.example.model.BotConfig
import com.example.vision.BlockVisionAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RealGameScreenshotTest {

    private fun loadWorkspaceImageOrSkip(relativePath: String): Bitmap {
        val candidates = listOf(
            File(relativePath),
            File("..", relativePath)
        )
        val file = candidates.firstOrNull { it.exists() }
        assumeTrue("Optional screenshot fixture not present: $relativePath", file != null)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file!!.absolutePath, opts)
            ?: error("Failed to decode ${file.absolutePath}")
    }

    @Test
    fun `analyzes user game screenshot 1 (right jump)`() {
        val bmp = loadWorkspaceImageOrSkip("public/assets/aistudio/Screenshot_20261116_073732_4fd83fcee830ed007aacc9cec66270f7_30a41bc7.jpg")
        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig())
        println("SCREENSHOT 1 => $result")
        assertTrue("Pawn should be detected on screenshot 1: ${result.statusMessage}", result.pawnFound)
        assertTrue("Block should be detected on screenshot 1: ${result.statusMessage}", result.blockFound)
        assertTrue("S1 block width should be > 200px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) > 200f)
        assertTrue("S1 pawn X should be on left side (< 300), got ${result.pawnCenterX}", result.pawnCenterX < 300f)
        assertTrue("S1 block X should be on right side (> 450), got ${result.blockBounds?.center?.x}", (result.blockBounds?.center?.x ?: 0f) > 450f)
    }

    @Test
    fun `analyzes user game screenshot 2 (left chest jump without platform rim drift)`() {
        val bmp = loadWorkspaceImageOrSkip("public/assets/aistudio/Screenshot_20261116_073749_4fd83fcee830ed007aacc9cec66270f7_227dd93d.jpg")
        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig())
        println("SCREENSHOT 2 => $result")
        assertTrue("Pawn should be detected on screenshot 2: ${result.statusMessage}", result.pawnFound)
        assertTrue("S2 pawn base Y should not drift onto blue rim (< 1005), got ${result.pawnBaseY}", result.pawnBaseY < 1005f)
        assertTrue("Block should be detected on screenshot 2: ${result.statusMessage}", result.blockFound)
        assertTrue("S2 chest width should be > 200px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) > 200f)
        assertTrue("S2 pawn X should be on right side (> 420), got ${result.pawnCenterX}", result.pawnCenterX > 420f)
        assertTrue("S2 chest X should be on left side (< 260), got ${result.blockBounds?.center?.x}", (result.blockBounds?.center?.x ?: 999f) < 260f)
    }

    @Test
    fun `analyzes user game screenshot 3 (score 47 occluded pawn, ninja block, HUD excluded)`() {
        val bmp = loadWorkspaceImageOrSkip("public/assets/aistudio/Screenshot_20261116_084330_4fd83fcee830ed007aacc9cec66270f7_41ad5824.jpg")
        // Exclude the floating HUD rectangle shown in screenshot 3
        val hudRect = Rect(30, 280, 490, 475)
        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig(), excludedRect = hudRect)
        println("SCREENSHOT 3 => $result")
        assertTrue("Pawn should be detected on screenshot 3: ${result.statusMessage}", result.pawnFound)
        assertTrue("S3 pawn X should lock on real pawn (~326), not ninja block (~520), got ${result.pawnCenterX}", result.pawnCenterX in 295f..360f)
        assertTrue("Block should be detected on screenshot 3: ${result.statusMessage}", result.blockFound)
        assertTrue("S3 target block X should lock on ninja block (~505), got ${result.blockBounds?.center?.x}", (result.blockBounds?.center?.x ?: 0f) in 460f..560f)
        assertTrue("S3 ninja block width should be > 200px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) > 200f)
    }

    @Test
    fun `analyzes user small coffee cup screenshots without stalling`() {
        val cases = listOf(
            // (filename, expectedPawnRightOfCup, hudRect)
            Triple("public/assets/aistudio/Screenshot_20261116_211831_4fd83fcee830ed007aacc9cec66270f7_46c588a8.jpg", true, Rect(30, 110, 420, 405)),
            Triple("public/assets/aistudio/Screenshot_20261116_215020_4fd83fcee830ed007aacc9cec66270f7_3eefc20f.jpg", true, null),
            Triple("public/assets/aistudio/Screenshot_20261116_223218_4fd83fcee830ed007aacc9cec66270f7_2fb0f917.jpg", false, Rect(30, 145, 455, 430)),
            Triple("public/assets/aistudio/Screenshot_20261117_060923_4fd83fcee830ed007aacc9cec66270f7_32305e80.jpg", false, null)
        )
        for ((idx, spec) in cases.withIndex()) {
            val (path, cupIsLeft, hudRect) = spec
            val bmp = loadWorkspaceImageOrSkip(path)
            val res = BlockVisionAnalyzer.analyze(bmp, BotConfig(), excludedRect = hudRect)
            println("CUP SHOT ${idx + 4} => $res")
            assertTrue("Cup shot ${idx + 4}: Pawn should be detected (${res.statusMessage})", res.pawnFound)
            assertTrue("Cup shot ${idx + 4}: Small coffee cup block should be detected (${res.statusMessage})", res.blockFound)
            val blk = res.blockBounds!!
            assertTrue(
                "Cup shot ${idx + 4}: width should be in MINI range (68..136px), got ${blk.widthPx}",
                blk.widthPx in 68f..138f
            )
            assertTrue(
                "Cup shot ${idx + 4}: targetBlockProfile should be MINI, got ${res.targetBlockProfile}",
                res.targetBlockProfile == "MINI"
            )
            if (cupIsLeft) {
                assertTrue("Cup shot ${idx + 4}: cup (${blk.center.x}) must be left of pawn (${res.pawnCenterX})", blk.center.x < res.pawnCenterX - 100f)
            } else {
                assertTrue("Cup shot ${idx + 4}: cup (${blk.center.x}) must be right of pawn (${res.pawnCenterX})", blk.center.x > res.pawnCenterX + 100f)
            }
        }
    }
}
