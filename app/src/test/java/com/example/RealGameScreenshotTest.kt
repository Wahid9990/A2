package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        assertTrue("Pawn should be detected on screenshot 1: ${result.statusMessage}", result.pawnFound)
        assertTrue("Block should be detected on screenshot 1: ${result.statusMessage}", result.blockFound)
        assertTrue("S1 block width should be > 200px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) > 200f)
        assertTrue("S1 pawn X should be on left side (< 300), got ${result.pawnCenterX}", result.pawnCenterX < 300f)
        assertTrue("S1 block X should be on right side (> 450), got ${result.blockBounds?.center?.x}", (result.blockBounds?.center?.x ?: 0f) > 450f)
    }

    @Test
    fun `analyzes user game screenshot 2 (left chest jump)`() {
        val bmp = loadWorkspaceImageOrSkip("public/assets/aistudio/Screenshot_20261116_073749_4fd83fcee830ed007aacc9cec66270f7_227dd93d.jpg")
        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig())
        assertTrue("Pawn should be detected on screenshot 2: ${result.statusMessage}", result.pawnFound)
        assertTrue("Block should be detected on screenshot 2: ${result.statusMessage}", result.blockFound)
        assertTrue("S2 chest width should be > 200px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) > 200f)
        assertTrue("S2 pawn X should be on right side (> 420), got ${result.pawnCenterX}", result.pawnCenterX > 420f)
        assertTrue("S2 chest X should be on left side (< 260), got ${result.blockBounds?.center?.x}", (result.blockBounds?.center?.x ?: 999f) < 260f)
    }
}
