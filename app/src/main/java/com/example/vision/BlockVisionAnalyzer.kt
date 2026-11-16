package com.example.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.example.model.BlockBounds
import com.example.model.BotConfig
import com.example.model.FormulaMode
import com.example.model.PointF2D
import com.example.model.VisionAnalysisResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object BlockVisionAnalyzer {

    fun analyze(bitmap: Bitmap, config: BotConfig): VisionAnalysisResult {
        val startNs = System.nanoTime()
        val width = bitmap.width
        val height = bitmap.height

        if (width < 40 || height < 40) {
            return VisionAnalysisResult(
                imageWidth = width,
                imageHeight = height,
                roiTopY = 0f,
                roiBottomY = height.toFloat(),
                pawnFound = false,
                pawnCenterX = 0f,
                pawnBaseY = 0f,
                pawnPixelCount = 0,
                blockFound = false,
                blockBounds = null,
                rawDistancePx = 0f,
                normalizedDistancePx = 0f,
                computedDurationMs = 0L,
                safeMarginMs = 0L,
                analysisTimeMs = 0L,
                statusMessage = "Frame too small for analysis"
            )
        }

        val roiTop = (height * config.roiTopFraction.coerceIn(0.05f, 0.70f)).roundToInt()
        val roiBottom = (height * config.roiBottomFraction.coerceIn(0.30f, 0.95f)).roundToInt()
            .coerceAtLeast(roiTop + 20)
            .coerceAtMost(height - 1)

        val roiHeight = roiBottom - roiTop + 1
        val pixels = IntArray(width * roiHeight)
        bitmap.getPixels(pixels, 0, width, 0, roiTop, width, roiHeight)

        val targetR = (config.pawnColorRgb shr 16) and 0xFF
        val targetG = (config.pawnColorRgb shr 8) and 0xFF
        val targetB = config.pawnColorRgb and 0xFF
        val pawnTolSq = config.pawnColorTolerance * config.pawnColorTolerance * 3

        val step = max(1, width / 540)
        val marginX = max(8, (width * 0.03f).roundToInt())
        val bgThresholdSq = config.bgDiffThreshold * config.bgDiffThreshold * 3

        // Step 1: Detect the Pawn cluster matching pawnColorRgb (and distinct from row background)
        val rawPawnXs = ArrayList<Int>(256)
        val rawPawnYs = ArrayList<Int>(256)

        var y = 0
        while (y < roiHeight) {
            val rowOffset = y * width
            val leftBg = pixels[rowOffset + marginX / 2]
            val rightBg = pixels[rowOffset + width - 1 - marginX / 2]
            var x = marginX
            while (x < width - marginX) {
                val c = pixels[rowOffset + x]
                if (isForegroundPixel(c, leftBg, rightBg, x, width, bgThresholdSq / 2)) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val dr = r - targetR
                    val dg = g - targetG
                    val db = b - targetB
                    val distSq = dr * dr + dg * dg + db * db
                    if (distSq <= pawnTolSq) {
                        rawPawnXs.add(x)
                        rawPawnYs.add(roiTop + y)
                    }
                }
                x += step
            }
            y += step
        }

        // Keep only the densest spatial cluster around the peak 2D bucket so distant shadows/noise cannot pull the pawn coordinate
        val cellSize = max(24, (width * 0.05f).roundToInt())
        val bucketCounts = HashMap<Long, Int>()
        for (i in rawPawnXs.indices) {
            val key = ((rawPawnXs[i] / cellSize).toLong() shl 32) or ((rawPawnYs[i] / cellSize).toLong() and 0xFFFFFFFFL)
            bucketCounts[key] = (bucketCounts[key] ?: 0) + 1
        }
        val peakEntry = bucketCounts.maxByOrNull { it.value }
        val pawnXs = ArrayList<Int>(256)
        val pawnYs = ArrayList<Int>(256)
        if (peakEntry != null) {
            val peakCx = ((peakEntry.key shr 32).toInt() + 0.5f) * cellSize
            val peakCy = ((peakEntry.key and 0xFFFFFFFFL).toInt() + 0.5f) * cellSize
            val clusterRadiusX = max(48f, width * 0.09f)
            val clusterRadiusY = max(90f, height * 0.10f)
            for (i in rawPawnXs.indices) {
                if (abs(rawPawnXs[i] - peakCx) <= clusterRadiusX && abs(rawPawnYs[i] - peakCy) <= clusterRadiusY) {
                    pawnXs.add(rawPawnXs[i])
                    pawnYs.add(rawPawnYs[i])
                }
            }
        }

        if (pawnXs.size < 6) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return VisionAnalysisResult(
                imageWidth = width,
                imageHeight = height,
                roiTopY = roiTop.toFloat(),
                roiBottomY = roiBottom.toFloat(),
                pawnFound = false,
                pawnCenterX = 0f,
                pawnBaseY = 0f,
                pawnPixelCount = pawnXs.size,
                blockFound = false,
                blockBounds = null,
                rawDistancePx = 0f,
                normalizedDistancePx = 0f,
                computedDurationMs = 0L,
                safeMarginMs = 0L,
                analysisTimeMs = elapsedMs,
                statusMessage = "Pawn not found (${pawnXs.size} px matched). Sample pawn color in Calibrate."
            )
        }

        pawnXs.sort()
        pawnYs.sort()
        val p10Idx = (pawnXs.size * 0.10f).toInt().coerceIn(0, pawnXs.lastIndex)
        val p90Idx = (pawnXs.size * 0.90f).toInt().coerceIn(0, pawnXs.lastIndex)
        val p95YIdx = (pawnYs.size * 0.95f).toInt().coerceIn(0, pawnYs.lastIndex)
        val p05YIdx = (pawnYs.size * 0.05f).toInt().coerceIn(0, pawnYs.lastIndex)

        val pawnCenterX = (pawnXs[p10Idx] + pawnXs[p90Idx]) / 2f
        val pawnBaseY = pawnYs[p95YIdx].toFloat()
        val pawnTopY = pawnYs[p05YIdx].toFloat()
        val pawnWidthEstimate = (pawnXs[p90Idx] - pawnXs[p10Idx]).coerceAtLeast((width * 0.045f).roundToInt())
        val pawnExclusionRadius = max((pawnWidthEstimate * 1.15f).roundToInt(), (width * 0.07f).roundToInt())

        // Step 2 & 3: Scan top-to-bottom in ROI, skipping small particles/stars until a valid platform block is traced
        val minRunLength = max(6, (width * 0.014f).roundToInt())
        val minValidBlockWidth = max(45, (width * 0.08f).roundToInt())
        val minValidBlockHeight = max(18, (height * 0.015f).roundToInt())
        val maxBlockScanY = (pawnBaseY - roiTop - 6).roundToInt().coerceIn(0, roiHeight - 1)

        var foundApexX = -1f
        var foundApexY = -1
        var widestRowY = 0
        var maxTopWidth = 0
        var widestLeftX = 0f
        var widestRightX = 0f
        var lastTopRowY = 0
        var endWidth = 0
        var blockFound = false

        var ry = 0
        while (ry <= maxBlockScanY) {
            val rowOffset = ry * width
            val leftBg = pixels[rowOffset + marginX / 2]
            val rightBg = pixels[rowOffset + width - 1 - marginX / 2]

            var candidateApexX = -1f
            var runStart = -1
            var runCount = 0

            var x = marginX
            while (x < width - marginX) {
                val absY = roiTop + ry
                val inPawnColumn = abs(x - pawnCenterX) < pawnExclusionRadius && absY >= (pawnTopY - 40f)
                if (!inPawnColumn && isForegroundPixel(pixels[rowOffset + x], leftBg, rightBg, x, width, bgThresholdSq)) {
                    if (runStart < 0) runStart = x
                    runCount++
                } else {
                    if (runCount >= minRunLength) {
                        candidateApexX = (runStart + (x - step)) / 2f
                        break
                    }
                    runStart = -1
                    runCount = 0
                }
                x += step
            }
            if (candidateApexX < 0f && runCount >= minRunLength) {
                candidateApexX = (runStart + (width - marginX)) / 2f
            }

            if (candidateApexX >= 0f) {
                // Trace downward from (candidateApexX, ry) to verify this is a real platform block (not a star/particle)
                var cWidestRowY = ry
                var cMaxTopWidth = 0
                var cWidestLeftX = candidateApexX
                var cWidestRightX = candidateApexX
                var cLastTopRowY = ry
                var cEndWidth = 0
                var plateauRows = 0
                val maxTraceY = min(roiHeight - 1, ry + (width * 0.25f).roundToInt())
                var traceCenterX = candidateApexX.roundToInt().coerceIn(marginX, width - marginX - 1)

                for (ty in ry..maxTraceY) {
                    val tOffset = ty * width
                    val tLeftBg = pixels[tOffset + marginX / 2]
                    val tRightBg = pixels[tOffset + width - 1 - marginX / 2]

                    val seedPx = pixels[tOffset + traceCenterX]
                    if (!isForegroundPixel(seedPx, tLeftBg, tRightBg, traceCenterX, width, bgThresholdSq)) {
                        if (ty - ry > 6) break
                        continue
                    }

                    var lx = traceCenterX
                    while (lx > marginX) {
                        val px = pixels[tOffset + lx - 1]
                        if (!isForegroundPixel(px, tLeftBg, tRightBg, lx - 1, width, bgThresholdSq)) break
                        lx--
                    }

                    var rx = traceCenterX
                    while (rx < width - marginX - 1) {
                        val px = pixels[tOffset + rx + 1]
                        if (!isForegroundPixel(px, tLeftBg, tRightBg, rx + 1, width, bgThresholdSq)) break
                        rx++
                    }

                    val rowWidth = rx - lx
                    if (rowWidth >= 4) {
                        cLastTopRowY = ty
                        cEndWidth = rowWidth
                        traceCenterX = ((lx + rx) / 2).coerceIn(marginX, width - marginX - 1)

                        if (rowWidth > cMaxTopWidth + 3) {
                            cMaxTopWidth = rowWidth
                            cWidestRowY = ty
                            cWidestLeftX = lx.toFloat()
                            cWidestRightX = rx.toFloat()
                            plateauRows = 0
                        } else if (cMaxTopWidth >= minValidBlockWidth) {
                            plateauRows++
                            if (plateauRows >= 16) {
                                break
                            }
                        }
                    }
                }

                val cHeight = cLastTopRowY - ry
                if (cMaxTopWidth >= minValidBlockWidth && cHeight >= minValidBlockHeight) {
                    foundApexX = candidateApexX
                    foundApexY = ry
                    widestRowY = cWidestRowY
                    maxTopWidth = cMaxTopWidth
                    widestLeftX = cWidestLeftX
                    widestRightX = cWidestRightX
                    lastTopRowY = cLastTopRowY
                    endWidth = cEndWidth
                    blockFound = true
                    break
                } else {
                    // Reject tiny star/sparkle and resume scanning below it
                    ry = max(ry + 1, cLastTopRowY + 2)
                    continue
                }
            }
            ry++
        }

        if (!blockFound) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return VisionAnalysisResult(
                imageWidth = width,
                imageHeight = height,
                roiTopY = roiTop.toFloat(),
                roiBottomY = roiBottom.toFloat(),
                pawnFound = true,
                pawnCenterX = pawnCenterX,
                pawnBaseY = pawnBaseY,
                pawnPixelCount = pawnXs.size,
                blockFound = false,
                blockBounds = null,
                rawDistancePx = 0f,
                normalizedDistancePx = 0f,
                computedDurationMs = 0L,
                safeMarginMs = 0L,
                analysisTimeMs = elapsedMs,
                statusMessage = "Pawn locked at (${pawnCenterX.roundToInt()}, ${pawnBaseY.roundToInt()}), but next block not found."
            )
        }

        val isIsometricDiamond = widestRowY > foundApexY + 6
        val blockCenterYRel = if (isIsometricDiamond) {
            widestRowY.toFloat()
        } else {
            (foundApexY + lastTopRowY) / 2f
        }
        val blockCenterX = (foundApexX + (widestLeftX + widestRightX) / 2f) / 2f
        val blockCenterY = roiTop + blockCenterYRel
        val blockTopY = (roiTop + foundApexY).toFloat()
        val blockBottomY = (roiTop + lastTopRowY).toFloat()
        val blockWidthPx = maxTopWidth.coerceAtLeast(minRunLength).toFloat()
        val blockHeightPx = (lastTopRowY - foundApexY).coerceAtLeast(1).toFloat()

        // Step 4: Distance & Duration Calculation
        val dx = blockCenterX - pawnCenterX
        val dy = blockCenterY - pawnBaseY
        val rawDistancePx = hypot(dx, dy)
        val scaleTo1080 = 1080f / width.toFloat()
        val normalizedDistancePx = rawDistancePx * scaleTo1080

        val durationMs = computeDurationMs(normalizedDistancePx, config)
        val normalizedBlockWidth = blockWidthPx * scaleTo1080
        val safeMarginMs = (config.kFactor * (normalizedBlockWidth * 0.35f)).roundToLong().coerceAtLeast(5L)

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        return VisionAnalysisResult(
            imageWidth = width,
            imageHeight = height,
            roiTopY = roiTop.toFloat(),
            roiBottomY = roiBottom.toFloat(),
            pawnFound = true,
            pawnCenterX = pawnCenterX,
            pawnBaseY = pawnBaseY,
            pawnPixelCount = pawnXs.size,
            blockFound = true,
            blockBounds = BlockBounds(
                apex = PointF2D(foundApexX, blockTopY),
                center = PointF2D(blockCenterX, blockCenterY),
                leftEdgeX = widestLeftX,
                rightEdgeX = widestRightX,
                topY = blockTopY,
                bottomY = blockBottomY,
                widthPx = blockWidthPx,
                heightPx = blockHeightPx
            ),
            rawDistancePx = rawDistancePx,
            normalizedDistancePx = normalizedDistancePx,
            computedDurationMs = durationMs,
            safeMarginMs = safeMarginMs,
            analysisTimeMs = elapsedMs,
            statusMessage = "Locked target: D=${rawDistancePx.roundToInt()}px · W=${blockWidthPx.roundToInt()}px → Hold ${durationMs}ms"
        )
    }

    fun computeDurationMs(normalizedDistancePx: Float, config: BotConfig): Long {
        val raw = when (config.formulaMode) {
            FormulaMode.LINEAR -> config.kFactor * normalizedDistancePx + config.offsetMs
            FormulaMode.QUADRATIC ->
                config.quadFactor * normalizedDistancePx * normalizedDistancePx +
                    config.kFactor * normalizedDistancePx +
                    config.offsetMs
        }
        return raw.roundToLong().coerceIn(config.minPressMs, config.maxPressMs)
    }

    private fun isForegroundPixel(
        pixel: Int,
        leftBg: Int,
        rightBg: Int,
        x: Int,
        width: Int,
        thresholdSq: Int
    ): Boolean {
        val t = x.toFloat() / width.toFloat()
        val bgR = (((leftBg shr 16) and 0xFF) * (1f - t) + ((rightBg shr 16) and 0xFF) * t).roundToInt()
        val bgG = (((leftBg shr 8) and 0xFF) * (1f - t) + ((rightBg shr 8) and 0xFF) * t).roundToInt()
        val bgB = ((leftBg and 0xFF) * (1f - t) + (rightBg and 0xFF) * t).roundToInt()

        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF

        val dr = r - bgR
        val dg = g - bgG
        val db = b - bgB
        return (dr * dr + dg * dg + db * db) > thresholdSq
    }

    private fun colorDistSq(c1: Int, c2: Int): Int {
        val dr = ((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF)
        val dg = ((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF)
        val db = (c1 and 0xFF) - (c2 and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    /**
     * Generates a synthetic arcade block-jumping frame for testing vision & formula calibration
     * directly inside the app before testing on a live third-party game.
     */
    fun generateSyntheticTestFrame(
        width: Int = 720,
        height: Int = 1280,
        pawnColorRgb: Int = 0x383652,
        targetBlockWidthPx: Float = 170f,
        distanceFactor: Float = 0.65f
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.rgb(214, 232, 245), Color.rgb(245, 224, 210)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Current platform (bottom-left isometric block)
        val curCx = width * 0.28f
        val curCy = height * 0.63f
        drawIsometricBlock(canvas, curCx, curCy, 190f, 95f, Color.rgb(250, 250, 250), Color.rgb(212, 216, 225), Color.rgb(185, 190, 204))

        // Next target block (upper-right isometric block)
        val targetCx = curCx + (width * 0.48f * distanceFactor.coerceIn(0.35f, 1.0f))
        val targetCy = curCy - (height * 0.22f * distanceFactor.coerceIn(0.35f, 1.0f))
        val targetH = targetBlockWidthPx * 0.52f
        drawIsometricBlock(canvas, targetCx, targetCy, targetBlockWidthPx, targetH, Color.rgb(255, 196, 92), Color.rgb(225, 162, 65), Color.rgb(190, 128, 45))

        // Pawn standing on current block
        val pawnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(
                (pawnColorRgb shr 16) and 0xFF,
                (pawnColorRgb shr 8) and 0xFF,
                pawnColorRgb and 0xFF
            )
        }
        val bodyPath = Path().apply {
            moveTo(curCx - 14f, curCy - 12f)
            lineTo(curCx + 14f, curCy - 12f)
            lineTo(curCx + 9f, curCy - 56f)
            lineTo(curCx - 9f, curCy - 56f)
            close()
        }
        canvas.drawPath(bodyPath, pawnPaint)
        canvas.drawOval(curCx - 16f, curCy - 18f, curCx + 16f, curCy - 4f, pawnPaint)
        canvas.drawCircle(curCx, curCy - 68f, 12f, pawnPaint)

        return bmp
    }

    private fun drawIsometricBlock(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        topColor: Int,
        leftColor: Int,
        rightColor: Int
    ) {
        val hw = w / 2f
        val hh = h / 2f
        val depth = h * 0.9f

        val topPath = Path().apply {
            moveTo(cx, cy - hh)
            lineTo(cx + hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx - hw, cy)
            close()
        }
        val leftPath = Path().apply {
            moveTo(cx - hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx, cy + hh + depth)
            lineTo(cx - hw, cy + depth)
            close()
        }
        val rightPath = Path().apply {
            moveTo(cx + hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx, cy + hh + depth)
            lineTo(cx + hw, cy + depth)
            close()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = leftColor
        canvas.drawPath(leftPath, paint)
        paint.color = rightColor
        canvas.drawPath(rightPath, paint)
        paint.color = topColor
        canvas.drawPath(topPath, paint)
    }
}
