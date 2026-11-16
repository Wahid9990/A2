package com.example.model

enum class FormulaMode(val label: String) {
    LINEAR("Linear (T = k·D + b)"),
    QUADRATIC("Quadratic (T = a·D² + k·D + b)")
}

data class BotConfig(
    val formulaMode: FormulaMode = FormulaMode.LINEAR,
    val kFactor: Float = 1.26f,
    val offsetMs: Float = 38f,
    val quadFactor: Float = 0.00025f,
    val pawnColorRgb: Int = 0x3E3353,
    val pawnColorTolerance: Int = 40,
    val bgDiffThreshold: Int = 38,
    val roiTopFraction: Float = 0.28f,
    val roiBottomFraction: Float = 0.72f,
    val tapXFraction: Float = 0.50f,
    val tapYFraction: Float = 0.84f,
    val jumpCooldownMs: Long = 1800L,
    val minPressMs: Long = 90L,
    val maxPressMs: Long = 1600L
)

data class PointF2D(
    val x: Float,
    val y: Float
)

data class BlockBounds(
    val apex: PointF2D,
    val center: PointF2D,
    val leftEdgeX: Float,
    val rightEdgeX: Float,
    val topY: Float,
    val bottomY: Float,
    val widthPx: Float,
    val heightPx: Float
)

data class VisionAnalysisResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val roiTopY: Float,
    val roiBottomY: Float,
    val pawnFound: Boolean,
    val pawnCenterX: Float,
    val pawnBaseY: Float,
    val pawnPixelCount: Int,
    val blockFound: Boolean,
    val blockBounds: BlockBounds?,
    val rawDistancePx: Float,
    val normalizedDistancePx: Float,
    val computedDurationMs: Long,
    val safeMarginMs: Long,
    val analysisTimeMs: Long,
    val statusMessage: String
)

data class BotLogEntry(
    val id: Long,
    val timestamp: String,
    val message: String,
    val isError: Boolean = false
)
