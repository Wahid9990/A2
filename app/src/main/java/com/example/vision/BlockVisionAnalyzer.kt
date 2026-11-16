package com.example.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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

    private data class PawnCandidate(
        val xs: List<Int>,
        val ys: List<Int>,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val collarHits: Int,
        val collarCenterY: Float,
        val score: Int
    )

    private data class BlockTrace(
        val apexX: Float,
        val apexY: Int,
        val widestRowY: Int,
        val maxTopWidth: Int,
        val widestLeftX: Float,
        val widestRightX: Float,
        val lastTopRowY: Int
    )

    fun analyze(
        bitmap: Bitmap,
        config: BotConfig,
        excludedRect: Rect? = null
    ): VisionAnalysisResult {
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

        val step = max(1, width / 540)
        val marginX = max(8, (width * 0.03f).roundToInt())
        val bgThresholdSq = config.bgDiffThreshold * config.bgDiffThreshold * 3

        // Step 1: Detect the Pawn using connected-component clustering + collar/silhouette scoring
        val pawn = detectPawn(
            pixels = pixels,
            width = width,
            height = height,
            roiTop = roiTop,
            roiHeight = roiHeight,
            marginX = marginX,
            step = step,
            config = config,
            bgThresholdSq = bgThresholdSq,
            excludedRect = excludedRect
        )

        if (pawn == null) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return VisionAnalysisResult(
                imageWidth = width,
                imageHeight = height,
                roiTopY = roiTop.toFloat(),
                roiBottomY = roiBottom.toFloat(),
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
                analysisTimeMs = elapsedMs,
                statusMessage = "Pawn not found. Sample pawn color in Calibrate."
            )
        }

        val sortedXs = pawn.xs.sorted()
        val sortedYs = pawn.ys.sorted()
        val p10X = sortedXs[(sortedXs.size * 0.10f).toInt().coerceIn(0, sortedXs.lastIndex)]
        val p90X = sortedXs[(sortedXs.size * 0.90f).toInt().coerceIn(0, sortedXs.lastIndex)]
        val p05Y = sortedYs[(sortedYs.size * 0.05f).toInt().coerceIn(0, sortedYs.lastIndex)]
        val p95Y = sortedYs[(sortedYs.size * 0.95f).toInt().coerceIn(0, sortedYs.lastIndex)]

        val pawnCenterX = (p10X + p90X) / 2f
        val pawnTopY = p05Y.toFloat()
        val observedHeight = (p95Y - p05Y).toFloat()
        val expectedPawnHeight = width * 0.164f
        val coneVisibleBelowCollar = pawn.collarHits >= 3 &&
            pawn.collarCenterY > 0f &&
            (p95Y - pawn.collarCenterY) >= (width * 0.052f)
        val isBottomOccluded = !coneVisibleBelowCollar && observedHeight < expectedPawnHeight * 0.72f
        val pawnBaseY = if (isBottomOccluded) {
            // Pawn lower cone is genuinely occluded behind a tall foreground block (e.g., roof/chest);
            // project true resting foot from the golden collar or head top.
            if (pawn.collarHits >= 3 && pawn.collarCenterY > 0f) {
                (pawn.collarCenterY + width * 0.095f).coerceAtMost(roiBottom.toFloat())
            } else {
                (pawnTopY + expectedPawnHeight).coerceAtMost(roiBottom.toFloat())
            }
        } else {
            p95Y.toFloat()
        }

        val pawnWidthEstimate = (p90X - p10X).coerceAtLeast((width * 0.045f).roundToInt())
        val pawnExclusionRadius = max((pawnWidthEstimate * 1.35f).roundToInt(), (width * 0.08f).roundToInt())

        // Step 2: Three-pass target block detection (opaque, translucent/glass, and subtle background-matching blocks)
        val minRunLength = max(6, (width * 0.014f).roundToInt())
        val minValidBlockWidth = max(72, (width * 0.150f).roundToInt())
        val minValidBlockHeight = max(24, (height * 0.020f).roundToInt())
        val translucentThreshold = max(12, (config.bgDiffThreshold * 0.36f).roundToInt())
        val translucentThresholdSq = translucentThreshold * translucentThreshold * 3
        val subtleThreshold = max(8, (config.bgDiffThreshold * 0.24f).roundToInt())
        val subtleThresholdSq = subtleThreshold * subtleThreshold * 3

        var block = scanForTargetBlock(
            pixels = pixels,
            width = width,
            roiTop = roiTop,
            roiHeight = roiHeight,
            marginX = marginX,
            step = step,
            pawnCenterX = pawnCenterX,
            pawnTopY = pawnTopY,
            pawnBaseY = pawnBaseY,
            pawnExclusionRadius = pawnExclusionRadius,
            excludedRect = excludedRect,
            thresholdSq = bgThresholdSq,
            minRunLength = minRunLength,
            minValidBlockWidth = minValidBlockWidth,
            minValidBlockHeight = minValidBlockHeight,
            rejectDarkShadows = false
        )

        if (block != null) {
            // Check upward for a semi-transparent / subtle top half above a colored lower half
            block = extendTranslucentTopApex(
                pixels = pixels,
                width = width,
                roiHeight = roiHeight,
                marginX = marginX,
                excludedRect = excludedRect,
                roiTop = roiTop,
                baseBlock = block,
                translucentThresholdSq = translucentThresholdSq
            )
        } else {
            // Pass 2: Translucent / chromatic low-contrast block fallback (rejecting neutral drop shadows & smooth sky glow)
            block = scanForTargetBlock(
                pixels = pixels,
                width = width,
                roiTop = roiTop,
                roiHeight = roiHeight,
                marginX = marginX,
                step = step,
                pawnCenterX = pawnCenterX,
                pawnTopY = pawnTopY,
                pawnBaseY = pawnBaseY,
                pawnExclusionRadius = pawnExclusionRadius,
                excludedRect = excludedRect,
                thresholdSq = translucentThresholdSq,
                minRunLength = minRunLength,
                minValidBlockWidth = minValidBlockWidth,
                minValidBlockHeight = minValidBlockHeight,
                rejectDarkShadows = true,
                requireSharpBoundary = true
            )
        }

        if (block == null) {
            // Pass 3: Background-matching subtle cube fallback (verifies internal 3D shading contrast & sharp edges)
            block = scanForTargetBlock(
                pixels = pixels,
                width = width,
                roiTop = roiTop,
                roiHeight = roiHeight,
                marginX = marginX,
                step = step,
                pawnCenterX = pawnCenterX,
                pawnTopY = pawnTopY,
                pawnBaseY = pawnBaseY,
                pawnExclusionRadius = pawnExclusionRadius,
                excludedRect = excludedRect,
                thresholdSq = subtleThresholdSq,
                minRunLength = minRunLength,
                minValidBlockWidth = minValidBlockWidth,
                minValidBlockHeight = max(minValidBlockHeight, (height * 0.028f).roundToInt()),
                rejectDarkShadows = true,
                requireInternalContrast = true,
                requireSharpBoundary = true
            )
        }

        if (block == null) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return VisionAnalysisResult(
                imageWidth = width,
                imageHeight = height,
                roiTopY = roiTop.toFloat(),
                roiBottomY = roiBottom.toFloat(),
                pawnFound = true,
                pawnCenterX = pawnCenterX,
                pawnBaseY = pawnBaseY,
                pawnPixelCount = pawn.xs.size,
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

        // Step 3: Compute isometric geometric landing center
        val isIsometricDiamond = block.widestRowY > block.apexY + 6
        val midpointX = (block.widestLeftX + block.widestRightX) / 2f
        val blockCenterX = block.apexX * 0.35f + midpointX * 0.65f
        val blockCenterYRel = if (isIsometricDiamond) {
            val projectedEquator = block.apexY + block.maxTopWidth * 0.285f
            (block.widestRowY * 0.5f + projectedEquator * 0.5f)
        } else {
            (block.apexY + block.lastTopRowY) / 2f
        }

        val blockCenterY = roiTop + blockCenterYRel
        val blockTopY = (roiTop + block.apexY).toFloat()
        val blockBottomY = (roiTop + block.lastTopRowY).toFloat()
        val blockWidthPx = block.maxTopWidth.coerceAtLeast(minRunLength).toFloat()
        val blockHeightPx = (block.lastTopRowY - block.apexY).coerceAtLeast(1).toFloat()

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
            pawnPixelCount = pawn.xs.size,
            blockFound = true,
            blockBounds = BlockBounds(
                apex = PointF2D(block.apexX, blockTopY),
                center = PointF2D(blockCenterX, blockCenterY),
                leftEdgeX = block.widestLeftX,
                rightEdgeX = block.widestRightX,
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

    data class FrameDiagnostics(
        val skyTopHex: String,
        val skyMidHex: String,
        val pawnSampleHex: String,
        val blockTopHex: String = "",
        val blockSideHex: String = ""
    )

    fun sampleFrameDiagnostics(
        bitmap: Bitmap,
        roiTopY: Float,
        roiBottomY: Float,
        pawnX: Float,
        pawnY: Float,
        blockX: Float = 0f,
        blockY: Float = 0f,
        blockBottomY: Float = 0f
    ): FrameDiagnostics {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 10 || h < 10) return FrameDiagnostics("#000000", "#000000", "#000000")
        fun hexAt(x: Int, y: Int): String {
            val px = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)) and 0xFFFFFF
            return String.format(java.util.Locale.US, "#%06X", px)
        }
        val topY = roiTopY.roundToInt().coerceIn(0, h - 1)
        val midY = ((roiTopY + roiBottomY) * 0.5f).roundToInt().coerceIn(0, h - 1)
        val samplePawnY = (pawnY - w * 0.045f).roundToInt().coerceIn(0, h - 1)
        val hasBlock = blockX > 1f && blockY > 1f
        val sideY = if (blockBottomY > blockY + 4f) blockBottomY.roundToInt() else (blockY + w * 0.055f).roundToInt()
        return FrameDiagnostics(
            skyTopHex = hexAt((w * 0.04f).roundToInt(), topY),
            skyMidHex = hexAt((w * 0.96f).roundToInt(), midY),
            pawnSampleHex = if (pawnX > 1f) hexAt(pawnX.roundToInt(), samplePawnY) else "#000000",
            blockTopHex = if (hasBlock) hexAt(blockX.roundToInt(), blockY.roundToInt()) else "",
            blockSideHex = if (hasBlock) hexAt((blockX - w * 0.04f).roundToInt(), sideY) else ""
        )
    }

    private fun detectPawn(
        pixels: IntArray,
        width: Int,
        height: Int,
        roiTop: Int,
        roiHeight: Int,
        marginX: Int,
        step: Int,
        config: BotConfig,
        bgThresholdSq: Int,
        excludedRect: Rect?
    ): PawnCandidate? {
        val targetR = (config.pawnColorRgb shr 16) and 0xFF
        val targetG = (config.pawnColorRgb shr 8) and 0xFF
        val targetB = config.pawnColorRgb and 0xFF
        val pawnTolSq = config.pawnColorTolerance * config.pawnColorTolerance * 3

        val cellSize = max(12, width / 48)
        val cellBuckets = HashMap<Long, Pair<MutableList<Int>, MutableList<Int>>>()

        var y = 0
        while (y < roiHeight) {
            val rowOffset = y * width
            val absY = roiTop + y
            val leftBg = pixels[rowOffset + marginX / 2]
            val rightBg = pixels[rowOffset + width - 1 - marginX / 2]
            var x = marginX
            while (x < width - marginX) {
                if (!isExcluded(x, absY, excludedRect)) {
                    val c = pixels[rowOffset + x]
                    if (isForegroundPixel(c, leftBg, rightBg, x, width, bgThresholdSq / 2)) {
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val dr = r - targetR
                        val dg = g - targetG
                        val db = b - targetB
                        if (dr * dr + dg * dg + db * db <= pawnTolSq) {
                            val gx = x / cellSize
                            val gy = absY / cellSize
                            val key = (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)
                            val pair = cellBuckets.getOrPut(key) {
                                ArrayList<Int>(16) to ArrayList(16)
                            }
                            pair.first.add(x)
                            pair.second.add(absY)
                        }
                    }
                }
                x += step
            }
            y += step
        }

        if (cellBuckets.isEmpty()) return null

        val visited = HashSet<Long>(cellBuckets.size)
        val queue = ArrayDeque<Long>()
        var bestCandidate: PawnCandidate? = null

        for (startKey in cellBuckets.keys) {
            if (!visited.add(startKey)) continue
            queue.clear()
            queue.add(startKey)

            val compXs = ArrayList<Int>(128)
            val compYs = ArrayList<Int>(128)
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE

            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                val data = cellBuckets[key] ?: continue
                for (i in data.first.indices) {
                    val px = data.first[i]
                    val py = data.second[i]
                    compXs.add(px)
                    compYs.add(py)
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                }

                val gx = (key shr 32).toInt()
                val gy = (key and 0xFFFFFFFFL).toInt()
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val neighbor = ((gx + dx).toLong() shl 32) or ((gy + dy).toLong() and 0xFFFFFFFFL)
                        if (cellBuckets.containsKey(neighbor) && visited.add(neighbor)) {
                            queue.add(neighbor)
                        }
                    }
                }
            }

            val minPawnPixels = max(24, (width * height) / 42000)
            val maxPawnPixels = max(3500, (width * height * 0.0038f).roundToInt())
            if (compXs.size !in minPawnPixels..maxPawnPixels) continue

            val compW = maxX - minX
            val compH = maxY - minY

            // Reject massive wide blocks (e.g., dark block walls)
            if (compW > width * 0.14f) continue
            if (compW > compH * 1.6f && compW > width * 0.08f) continue

            // Check for warm gold collar signature near this component
            var collarHits = 0
            var collarSumY = 0L
            val scanMinX = (minX - 6).coerceIn(marginX, width - marginX - 1)
            val scanMaxX = (maxX + 6).coerceIn(marginX, width - marginX - 1)
            val scanMinY = (minY - roiTop - 10).coerceIn(0, roiHeight - 1)
            val scanMaxY = (maxY - roiTop + 28).coerceIn(0, roiHeight - 1)
            var cy = scanMinY
            while (cy <= scanMaxY) {
                val rowOff = cy * width
                var cx = scanMinX
                while (cx <= scanMaxX) {
                    val px = pixels[rowOff + cx]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    if (r > 160 && g > 115 && b < 130 && (r - b) > 50) {
                        collarHits++
                        collarSumY += (roiTop + cy)
                    }
                    cx += 2
                }
                cy += 2
            }

            // Reject tiny UI artifacts / Game Over buttons / screen-edge modal cards lacking a golden collar
            val clippedAtRoiBottom = maxY >= roiTop + roiHeight - 3
            val extremeSideMargin = (minX + maxX) * 0.5f < width * 0.10f || (minX + maxX) * 0.5f > width * 0.90f
            if (collarHits < 2 && compXs.size < max(75, width / 8)) continue
            if (collarHits < 3 && (clippedAtRoiBottom || extremeSideMargin)) continue

            var score = compXs.size.coerceAtMost(2800)
            if (compW <= width * 0.09f) score += 1200
            if (compH >= compW * 1.1f) score += 1000
            if (collarHits >= 3) score += 6000
            if (compW > width * 0.105f) score -= 3000

            val collarCenterY = if (collarHits > 0) collarSumY.toFloat() / collarHits else -1f
            val candidate = PawnCandidate(
                xs = compXs,
                ys = compYs,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                collarHits = collarHits,
                collarCenterY = collarCenterY,
                score = score
            )
            if (bestCandidate == null || candidate.score > bestCandidate.score) {
                bestCandidate = candidate
            }
        }

        return bestCandidate
    }

    private fun scanForTargetBlock(
        pixels: IntArray,
        width: Int,
        roiTop: Int,
        roiHeight: Int,
        marginX: Int,
        step: Int,
        pawnCenterX: Float,
        pawnTopY: Float,
        pawnBaseY: Float,
        pawnExclusionRadius: Int,
        excludedRect: Rect?,
        thresholdSq: Int,
        minRunLength: Int,
        minValidBlockWidth: Int,
        minValidBlockHeight: Int,
        rejectDarkShadows: Boolean,
        requireInternalContrast: Boolean = false,
        requireSharpBoundary: Boolean = false
    ): BlockTrace? {
        val maxBlockScanY = (pawnBaseY - roiTop - 10).roundToInt().coerceIn(0, roiHeight - 1)
        val maxValidBlockWidth = (width * 0.45f).roundToInt()
        val minVerticalClimb = width * 0.075f

        var bestBlock: BlockTrace? = null
        var bestScore = Float.NEGATIVE_INFINITY

        var ry = 0
        while (ry <= maxBlockScanY) {
            val rowOffset = ry * width
            val absY = roiTop + ry
            val leftBg = pixels[rowOffset + marginX / 2]
            val rightBg = pixels[rowOffset + width - 1 - marginX / 2]

            var candidateApexX = -1f
            var runStart = -1
            var runCount = 0

            var x = marginX
            while (x < width - marginX) {
                val inPawnColumn = abs(x - pawnCenterX) < pawnExclusionRadius && absY >= (pawnTopY - 40f)
                val excluded = isExcluded(x, absY, excludedRect)
                val isFg = !inPawnColumn && !excluded && isValidBlockPixel(
                    pixels[rowOffset + x],
                    leftBg,
                    rightBg,
                    x,
                    width,
                    thresholdSq,
                    rejectDarkShadows
                )
                if (isFg) {
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
                val traced = traceBlockDownward(
                    pixels = pixels,
                    width = width,
                    roiTop = roiTop,
                    roiHeight = roiHeight,
                    marginX = marginX,
                    startY = ry,
                    apexX = candidateApexX,
                    minValidBlockWidth = minValidBlockWidth,
                    excludedRect = excludedRect,
                    thresholdSq = thresholdSq,
                    rejectDarkShadows = rejectDarkShadows
                )
                val cHeight = traced.lastTopRowY - ry
                val centerX = traced.apexX * 0.35f + ((traced.widestLeftX + traced.widestRightX) * 0.5f) * 0.65f
                val isIsometricDiamond = traced.widestRowY > traced.apexY + 6
                val centerYRel = if (isIsometricDiamond) {
                    val projectedEquator = traced.apexY + traced.maxTopWidth * 0.285f
                    (traced.widestRowY * 0.5f + projectedEquator * 0.5f)
                } else {
                    (traced.apexY + traced.lastTopRowY) / 2f
                }
                val centerY = roiTop + centerYRel

                val dxAbs = abs(centerX - pawnCenterX)
                val dyAbs = pawnBaseY - centerY
                val distFromPawn = hypot(dxAbs, dyAbs)
                val overlapsPawnBase = pawnCenterX in (traced.widestLeftX - 10f)..(traced.widestRightX + 10f) &&
                    pawnBaseY in (roiTop + ry - 8f)..(roiTop + traced.lastTopRowY + width * 0.14f)

                val contrastOk = !requireInternalContrast || hasInternalBlockContrast(
                    pixels = pixels,
                    width = width,
                    roiHeight = roiHeight,
                    traced = traced
                )
                val sharpBoundaryOk = (!requireSharpBoundary && !requireInternalContrast) || hasSharpBoundaryEdge(
                    pixels = pixels,
                    width = width,
                    roiHeight = roiHeight,
                    traced = traced,
                    marginX = marginX
                )

                val validGeometry = traced.maxTopWidth in minValidBlockWidth..maxValidBlockWidth &&
                    cHeight >= minValidBlockHeight &&
                    distFromPawn >= width * 0.15f &&
                    !overlapsPawnBase &&
                    dyAbs >= minVerticalClimb &&
                    dxAbs >= dyAbs * 0.25f &&
                    contrastOk &&
                    sharpBoundaryOk

                if (validGeometry) {
                    // Prefer the true next block (closest diagonal platform above the pawn rather than far 2nd blocks)
                    val isoRatio = dxAbs / dyAbs.coerceAtLeast(1f)
                    val isoBonus = if (isoRatio in 0.35f..1.85f) 400f else 0f
                    val sizeBonus = if (traced.maxTopWidth in (width * 0.18f).roundToInt()..(width * 0.42f).roundToInt()) 150f else 0f
                    val score = isoBonus + sizeBonus - distFromPawn
                    if (score > bestScore) {
                        bestScore = score
                        bestBlock = traced
                    }
                }
                ry = max(ry + 2, traced.lastTopRowY + 4)
                continue
            }
            ry++
        }
        return bestBlock
    }

    private fun colorDistSq(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    private fun hasInternalBlockContrast(
        pixels: IntArray,
        width: Int,
        roiHeight: Int,
        traced: BlockTrace
    ): Boolean {
        val cx = ((traced.widestLeftX + traced.widestRightX) * 0.5f).roundToInt().coerceIn(8, width - 9)
        val topSampleY = ((traced.apexY + traced.widestRowY) * 0.5f).roundToInt().coerceIn(0, roiHeight - 1)
        val sideSampleY = min(roiHeight - 1, traced.widestRowY + max(8, (traced.lastTopRowY - traced.apexY) / 3))
        val topPx = pixels[topSampleY * width + cx]
        val leftSidePx = pixels[sideSampleY * width + (cx - 12).coerceAtLeast(0)]
        val rightSidePx = pixels[sideSampleY * width + (cx + 12).coerceAtMost(width - 1)]

        // Real 3D blocks have strong top-vs-side or left-vs-right face contrast, unlike vertical sky gradients
        return maxOf(
            colorDistSq(topPx, leftSidePx),
            colorDistSq(topPx, rightSidePx),
            colorDistSq(leftSidePx, rightSidePx)
        ) >= 300
    }

    private fun hasSharpBoundaryEdge(
        pixels: IntArray,
        width: Int,
        roiHeight: Int,
        traced: BlockTrace,
        marginX: Int
    ): Boolean {
        val leftX = traced.widestLeftX.roundToInt()
        val rightX = traced.widestRightX.roundToInt()
        val outLeftX = (leftX - 10).coerceIn(marginX / 2, width - 1)
        val inLeftX = (leftX + 10).coerceIn(0, width - 1)
        val outRightX = (rightX + 10).coerceIn(0, width - 1 - marginX / 2)
        val inRightX = (rightX - 10).coerceIn(0, width - 1)

        val rowA = traced.widestRowY.coerceIn(0, roiHeight - 1)
        val rowB = (traced.widestRowY + 8).coerceIn(0, roiHeight - 1)

        val leftJump = max(
            colorDistSq(pixels[rowA * width + inLeftX], pixels[rowA * width + outLeftX]),
            colorDistSq(pixels[rowB * width + inLeftX], pixels[rowB * width + outLeftX])
        )
        val rightJump = max(
            colorDistSq(pixels[rowA * width + inRightX], pixels[rowA * width + outRightX]),
            colorDistSq(pixels[rowB * width + inRightX], pixels[rowB * width + outRightX])
        )

        // Smooth radial sky glow changes <= 50 across 20px; real block borders jump sharply
        return max(leftJump, rightJump) >= 240 && min(leftJump, rightJump) >= 110
    }

    private fun traceBlockDownward(
        pixels: IntArray,
        width: Int,
        roiTop: Int,
        roiHeight: Int,
        marginX: Int,
        startY: Int,
        apexX: Float,
        minValidBlockWidth: Int,
        excludedRect: Rect?,
        thresholdSq: Int,
        rejectDarkShadows: Boolean
    ): BlockTrace {
        var cWidestRowY = startY
        var cMaxTopWidth = 0
        var cWidestLeftX = apexX
        var cWidestRightX = apexX
        var cLastTopRowY = startY
        var plateauRows = 0
        val maxTraceY = min(roiHeight - 1, startY + (width * 0.18f).roundToInt())
        val maxWideningY = startY + (width * 0.145f).roundToInt()
        val maxReasonableWidth = (width * 0.45f).roundToInt()
        val maxSpikeJump = max(24, (width * 0.06f).roundToInt())
        var traceCenterX = apexX.roundToInt().coerceIn(marginX, width - marginX - 1)

        for (ty in startY..maxTraceY) {
            val tOffset = ty * width
            val absY = roiTop + ty
            val tLeftBg = pixels[tOffset + marginX / 2]
            val tRightBg = pixels[tOffset + width - 1 - marginX / 2]

            if (isExcluded(traceCenterX, absY, excludedRect)) {
                if (ty - startY > 6) break
                continue
            }

            val seedPx = pixels[tOffset + traceCenterX]
            if (!isValidBlockPixel(seedPx, tLeftBg, tRightBg, traceCenterX, width, thresholdSq, rejectDarkShadows)) {
                if (ty - startY > 6) break
                continue
            }

            var lx = traceCenterX
            while (lx > marginX) {
                if (isExcluded(lx - 1, absY, excludedRect)) break
                val px = pixels[tOffset + lx - 1]
                if (!isValidBlockPixel(px, tLeftBg, tRightBg, lx - 1, width, thresholdSq, rejectDarkShadows)) break
                lx--
            }

            var rx = traceCenterX
            while (rx < width - marginX - 1) {
                if (isExcluded(rx + 1, absY, excludedRect)) break
                val px = pixels[tOffset + rx + 1]
                if (!isValidBlockPixel(px, tLeftBg, tRightBg, rx + 1, width, thresholdSq, rejectDarkShadows)) break
                rx++
            }

            val rowWidth = rx - lx
            if (rowWidth >= 4) {
                // Stop if tracing downward bled sideways into an adjacent drop shadow or neighboring block
                if (cMaxTopWidth >= minValidBlockWidth &&
                    (rowWidth > maxReasonableWidth || rowWidth > cMaxTopWidth + maxSpikeJump)
                ) {
                    break
                }

                cLastTopRowY = ty
                traceCenterX = ((lx + rx) / 2).coerceIn(marginX, width - marginX - 1)

                if (ty <= maxWideningY && rowWidth > cMaxTopWidth + 3 && rowWidth <= maxReasonableWidth) {
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

        return BlockTrace(
            apexX = apexX,
            apexY = startY,
            widestRowY = cWidestRowY,
            maxTopWidth = cMaxTopWidth,
            widestLeftX = cWidestLeftX,
            widestRightX = cWidestRightX,
            lastTopRowY = cLastTopRowY
        )
    }

    /**
     * Handles half-transparent blocks where the upper half is faint translucent glass and the
     * lower half is solid/colored. Looks upward from [baseBlock.apexY] for a brighter/translucent top apex.
     */
    private fun extendTranslucentTopApex(
        pixels: IntArray,
        width: Int,
        roiHeight: Int,
        marginX: Int,
        excludedRect: Rect?,
        roiTop: Int,
        baseBlock: BlockTrace,
        translucentThresholdSq: Int
    ): BlockTrace {
        val searchStart = (baseBlock.apexY - (baseBlock.maxTopWidth * 0.42f).roundToInt()).coerceAtLeast(0)
        val probeHalfW = (baseBlock.maxTopWidth * 0.35f).roundToInt().coerceAtLeast(16)
        val centerX = ((baseBlock.widestLeftX + baseBlock.widestRightX) / 2f).roundToInt()
        val scanMinX = (centerX - probeHalfW).coerceAtLeast(marginX)
        val scanMaxX = (centerX + probeHalfW).coerceAtMost(width - marginX - 1)

        var newApexY = baseBlock.apexY
        var newApexX = baseBlock.apexX
        var consecutiveEmpty = 0

        for (ry in (baseBlock.apexY - 1) downTo searchStart) {
            val rowOffset = ry * width
            val absY = roiTop + ry
            val leftBg = pixels[rowOffset + marginX / 2]
            val rightBg = pixels[rowOffset + width - 1 - marginX / 2]

            var runStart = -1
            var runLen = 0
            var bestRunStart = -1
            var bestRunLen = 0

            for (x in scanMinX..scanMaxX) {
                if (!isExcluded(x, absY, excludedRect) &&
                    isValidBlockPixel(pixels[rowOffset + x], leftBg, rightBg, x, width, translucentThresholdSq, rejectDarkShadows = true)
                ) {
                    if (runStart < 0) runStart = x
                    runLen++
                    if (runLen > bestRunLen) {
                        bestRunLen = runLen
                        bestRunStart = runStart
                    }
                } else {
                    runStart = -1
                    runLen = 0
                }
            }

            if (bestRunLen >= 6) {
                newApexY = ry
                newApexX = bestRunStart + bestRunLen / 2f
                consecutiveEmpty = 0
            } else {
                consecutiveEmpty++
                if (consecutiveEmpty >= 4) break
            }
        }

        if (baseBlock.apexY - newApexY < 6) return baseBlock

        return traceBlockDownward(
            pixels = pixels,
            width = width,
            roiTop = roiTop,
            roiHeight = roiHeight,
            marginX = marginX,
            startY = newApexY,
            apexX = newApexX,
            minValidBlockWidth = max(72, (width * 0.150f).roundToInt()),
            excludedRect = excludedRect,
            thresholdSq = translucentThresholdSq,
            rejectDarkShadows = true
        ).let { extended ->
            if (extended.maxTopWidth >= baseBlock.maxTopWidth * 0.92f) extended else baseBlock
        }
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

    private fun isExcluded(x: Int, absY: Int, excludedRect: Rect?): Boolean {
        if (excludedRect == null) return false
        return x in (excludedRect.left - 10)..(excludedRect.right + 10) &&
            absY in (excludedRect.top - 10)..(excludedRect.bottom + 10)
    }

    private fun isValidBlockPixel(
        pixel: Int,
        leftBg: Int,
        rightBg: Int,
        x: Int,
        width: Int,
        thresholdSq: Int,
        rejectDarkShadows: Boolean
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

        if (rejectDarkShadows) {
            val pxLum = r * 3 + g * 6 + b
            val bgLum = bgR * 3 + bgG * 6 + bgB
            val maxChromaShift = maxOf(abs(dr - dg), abs(dg - db), abs(dr - db))
            // Reject neutral/desaturated dark drop shadows on the sky, while keeping colored dark/slate blocks
            if (pxLum < bgLum - 40 && maxChromaShift < 14) return false
        }

        return (dr * dr + dg * dg + db * db) > thresholdSq
    }

    private fun isForegroundPixel(
        pixel: Int,
        leftBg: Int,
        rightBg: Int,
        x: Int,
        width: Int,
        thresholdSq: Int
    ): Boolean {
        return isValidBlockPixel(pixel, leftBg, rightBg, x, width, thresholdSq, rejectDarkShadows = false)
    }

    /**
     * Generates a synthetic arcade block-jumping frame for testing vision & formula calibration
     * directly inside the app before testing on a live third-party game.
     */
    fun generateSyntheticTestFrame(
        width: Int = 720,
        height: Int = 1280,
        pawnColorRgb: Int = 0x3E3353,
        targetBlockWidthPx: Float = 170f,
        distanceFactor: Float = 0.65f,
        halfTransparentTarget: Boolean = false
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.rgb(94, 92, 157), Color.rgb(141, 148, 201)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Current platform (bottom-left isometric block)
        val curCx = width * 0.28f
        val curCy = height * 0.63f
        drawIsometricBlock(canvas, curCx, curCy, 190f, 95f, Color.rgb(250, 250, 250), Color.rgb(30, 142, 149), Color.rgb(22, 118, 125))

        // Next target block (upper-right isometric block)
        val targetCx = curCx + (width * 0.48f * distanceFactor.coerceIn(0.35f, 1.0f))
        val targetCy = curCy - (height * 0.22f * distanceFactor.coerceIn(0.35f, 1.0f))
        val targetH = targetBlockWidthPx * 0.52f
        if (halfTransparentTarget) {
            drawHalfTransparentBlock(canvas, targetCx, targetCy, targetBlockWidthPx, targetH)
        } else {
            drawIsometricBlock(canvas, targetCx, targetCy, targetBlockWidthPx, targetH, Color.rgb(255, 196, 92), Color.rgb(225, 162, 65), Color.rgb(190, 128, 45))
        }

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
            lineTo(curCx + 9f, curCy - 52f)
            lineTo(curCx - 9f, curCy - 52f)
            close()
        }
        canvas.drawPath(bodyPath, pawnPaint)
        canvas.drawOval(curCx - 16f, curCy - 18f, curCx + 16f, curCy - 4f, pawnPaint)
        val collarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 175, 98) }
        canvas.drawOval(curCx - 12f, curCy - 60f, curCx + 12f, curCy - 50f, collarPaint)
        canvas.drawCircle(curCx, curCy - 72f, 12f, pawnPaint)

        return bmp
    }

    private fun drawHalfTransparentBlock(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float
    ) {
        val hw = w / 2f
        val hh = h / 2f
        val depth = h * 0.85f

        val topGlass = Path().apply {
            moveTo(cx, cy - hh)
            lineTo(cx + hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx - hw, cy)
            close()
        }
        val leftSide = Path().apply {
            moveTo(cx - hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx, cy + hh + depth)
            lineTo(cx - hw, cy + depth)
            close()
        }
        val rightSide = Path().apply {
            moveTo(cx + hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx, cy + hh + depth)
            lineTo(cx + hw, cy + depth)
            close()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(230, 56, 189, 248)
        canvas.drawPath(leftSide, paint)
        paint.color = Color.argb(230, 14, 116, 144)
        canvas.drawPath(rightSide, paint)
        paint.color = Color.argb(72, 255, 255, 255)
        canvas.drawPath(topGlass, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(145, 255, 255, 255)
        canvas.drawPath(topGlass, paint)
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
