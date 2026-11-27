package com.custom.ridenavlink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class IconMaskData(
    val width: Int,
    val height: Int,
    val primaryMask: BooleanArray,
    val secondaryMask: BooleanArray
) {
    val fullMask: BooleanArray by lazy {
        BooleanArray(width * height) { i -> primaryMask[i] || secondaryMask[i] }
    }
}

data class ShapeClassificationResult(
    val maneuverCode: Byte,
    val confidenceScore: Float,
    val label: String,
    val debugSummary: String = ""
)

/**
 * Pure-Kotlin shape classifier for Google Maps turn-by-turn notification bitmaps.
 *
 * Uses a multi-stage pipeline:
 * 1. Dedicated Destination detector (Pin+Road left/right composite and single teardrop pin)
 * 2. Dedicated U-Turn detector (top arch + two legs + downward arrowhead side)
 * 3. Dual-tone multi-road classifier (Merge, Fork, Ramp, Keep) using primary vs faded secondary masks
 * 4. Normalized 32x32 template matching (dilated Dice) + arrow geometry descriptors
 *    (Straight, Slight L/R, Turn 90° L/R, Sharp 135° L/R)
 *
 * Roundabout detection remains in GoogleMapsIconParser (7-spoke angular ring search).
 */
object IconShapeClassifier {

    private const val NORM_SIZE = 32

    private data class BBox(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val count: Int
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val midX: Double get() = (minX + maxX) / 2.0
        val midY: Double get() = (minY + maxY) / 2.0
    }

    private data class TemplateEntry(
        val code: Byte,
        val name: String,
        val mask: BooleanArray,
        val dilated: BooleanArray
    )

    private val canonicalTemplates: List<TemplateEntry> by lazy { buildCanonicalTemplates() }

    fun classify(data: IconMaskData): ShapeClassificationResult? {
        val fullBox = computeBBox(data.fullMask, data.width, data.height) ?: return null
        if (fullBox.count < 12 || fullBox.width < 4 || fullBox.height < 4) return null

        // 1. Dedicated Destination detector (all 3 Google Maps variants: Pin+Road L/R, single Pin)
        detectDestination(data.fullMask, data.width, data.height, fullBox)?.let { return it }

        val primaryBox = computeBBox(data.primaryMask, data.width, data.height)
        val activeMask = if (primaryBox != null && primaryBox.count >= 12) data.primaryMask else data.fullMask
        val activeBox = if (primaryBox != null && primaryBox.count >= 12) primaryBox else fullBox

        // 2. Dedicated U-Turn detector (on active mask so faded branches don't confuse it)
        detectUTurn(activeMask, data.width, activeBox)?.let { return it }

        // 3. Dual-tone multi-road detector (Merge, Fork, Ramp, Keep) when faded secondary road is present
        val secondaryBox = computeBBox(data.secondaryMask, data.width, data.height)
        if (primaryBox != null && secondaryBox != null &&
            primaryBox.count >= 12 &&
            secondaryBox.count >= max(8, (fullBox.count * 0.11).toInt())
        ) {
            detectDualToneManeuver(data, fullBox, primaryBox, secondaryBox)?.let { return it }
        }

        // 4. Hybrid 32x32 Template Matching + Geometric Arrow Descriptor classification
        return classifySinglePathArrow(activeMask, data.width, data.height, activeBox)
    }

    /**
     * Checks whether a bitmap looks like a Destination icon (used as a guard against
     * misclassifying a destination pin's small circular hole as a roundabout ring).
     */
    fun looksLikeDestination(mask: BooleanArray, width: Int, height: Int): Boolean {
        val box = computeBBox(mask, width, height) ?: return false
        return detectDestination(mask, width, height, box) != null
    }

    // -------------------------------------------------------------------------
    // Stage 1: Destination Detector (Pin+Road Left/Right & Single Centered Pin)
    // -------------------------------------------------------------------------

    private fun detectDestination(
        mask: BooleanArray,
        w: Int,
        h: Int,
        box: BBox
    ): ShapeClassificationResult? {
        // 1A. Composite Pin + Perspective Road via 2D Connected Components (handles X-overlapping Pin & Road!)
        detectCompositePinAndRoadByComponents(mask, w, box)?.let { return it }

        // 1B. Composite Pin + Perspective Road via vertical valley (handles slightly touching sub-shapes)
        detectCompositePinAndRoad(mask, w, box)?.let { return it }

        // 1C. Single centered teardrop pin (with or without inner hole)
        if (isSingleTeardropPin(mask, w, box)) {
            return ShapeClassificationResult(
                maneuverCode = BajajProtocol.ARROW_DESTINATION_SOLID,
                confidenceScore = 0.90f,
                label = "dest:pin_center",
                debugSummary = "single_pin"
            )
        }

        return null
    }

    private fun detectCompositePinAndRoadByComponents(
        mask: BooleanArray,
        w: Int,
        box: BBox
    ): ShapeClassificationResult? {
        if (box.width < 8 || box.height < 8) return null
        val aspect = box.width.toDouble() / box.height.toDouble()
        if (aspect < 0.72) return null

        val visited = BooleanArray(mask.size)
        val components = mutableListOf<BBox>()
        val queue = IntArray(box.width * box.height)

        for (gy in box.minY..box.maxY) {
            for (gx in box.minX..box.maxX) {
                val startIdx = gy * w + gx
                if (!mask[startIdx] || visited[startIdx]) continue

                var head = 0
                var tail = 0
                queue[tail++] = startIdx
                visited[startIdx] = true

                var cMinX = gx
                var cMaxX = gx
                var cMinY = gy
                var cMaxY = gy
                var cCount = 0

                while (head < tail) {
                    val idx = queue[head++]
                    val cy = idx / w
                    val cx = idx % w
                    cCount++
                    if (cx < cMinX) cMinX = cx
                    if (cx > cMaxX) cMaxX = cx
                    if (cy < cMinY) cMinY = cy
                    if (cy > cMaxY) cMaxY = cy

                    // 4-connected neighbors
                    val n1 = idx - 1
                    if (cx > box.minX && mask[n1] && !visited[n1]) { visited[n1] = true; queue[tail++] = n1 }
                    val n2 = idx + 1
                    if (cx < box.maxX && mask[n2] && !visited[n2]) { visited[n2] = true; queue[tail++] = n2 }
                    val n3 = idx - w
                    if (cy > box.minY && mask[n3] && !visited[n3]) { visited[n3] = true; queue[tail++] = n3 }
                    val n4 = idx + w
                    if (cy < box.maxY && mask[n4] && !visited[n4]) { visited[n4] = true; queue[tail++] = n4 }
                }

                if (cCount >= max(6, (box.count * 0.08).toInt())) {
                    components.add(BBox(cMinX, cMaxX, cMinY, cMaxY, cCount))
                }
            }
        }

        if (components.size < 2) return null

        // Group components into left-half vs right-half by their horizontal center
        val leftComps = components.filter { it.midX < box.midX }
        val rightComps = components.filter { it.midX >= box.midX }
        if (leftComps.isEmpty() || rightComps.isEmpty()) return null

        fun mergeBoxes(list: List<BBox>): BBox {
            var mnX = Int.MAX_VALUE; var mxX = Int.MIN_VALUE
            var mnY = Int.MAX_VALUE; var mxY = Int.MIN_VALUE
            var cnt = 0
            for (b in list) {
                if (b.minX < mnX) mnX = b.minX
                if (b.maxX > mxX) mxX = b.maxX
                if (b.minY < mnY) mnY = b.minY
                if (b.maxY > mxY) mxY = b.maxY
                cnt += b.count
            }
            return BBox(mnX, mxX, mnY, mxY, cnt)
        }

        val leftGroup = mergeBoxes(leftComps)
        val rightGroup = mergeBoxes(rightComps)
        val minGroupPixels = max(8, (box.count * 0.16).toInt())
        if (leftGroup.count < minGroupPixels || rightGroup.count < minGroupPixels) return null

        // Check Pin on Left + Road on Right (or vice versa)
        if (isPinSubShape(mask, w, leftGroup, box) && isRoadSubShape(mask, w, rightGroup, box)) {
            return ShapeClassificationResult(
                maneuverCode = BajajProtocol.ARROW_DESTINATION_SOLID,
                confidenceScore = 0.95f,
                label = "dest:cc_pin_left_road_right",
                debugSummary = "comps=${components.size}"
            )
        }
        if (isPinSubShape(mask, w, rightGroup, box) && isRoadSubShape(mask, w, leftGroup, box)) {
            return ShapeClassificationResult(
                maneuverCode = BajajProtocol.ARROW_DESTINATION_SOLID,
                confidenceScore = 0.95f,
                label = "dest:cc_road_left_pin_right",
                debugSummary = "comps=${components.size}"
            )
        }
        return null
    }

    private fun detectCompositePinAndRoad(
        mask: BooleanArray,
        w: Int,
        box: BBox
    ): ShapeClassificationResult? {
        if (box.width < 8 || box.height < 8) return null
        val aspect = box.width.toDouble() / box.height.toDouble()
        if (aspect < 0.72) return null

        // Column projection to find a vertical valley separating left and right sub-icons
        val colCounts = IntArray(box.width)
        for (gy in box.minY..box.maxY) {
            val rowOff = gy * w
            for (gx in box.minX..box.maxX) {
                if (mask[rowOff + gx]) {
                    colCounts[gx - box.minX]++
                }
            }
        }

        val searchStart = (box.width * 0.28).toInt().coerceAtLeast(2)
        val searchEnd = (box.width * 0.72).toInt().coerceAtMost(box.width - 3)
        if (searchStart >= searchEnd) return null

        var bestSplit = -1
        var bestValleyVal = Int.MAX_VALUE
        for (i in searchStart..searchEnd) {
            val v = colCounts[i]
            if (v < bestValleyVal) {
                bestValleyVal = v
                bestSplit = i
            }
        }

        // Valley must be completely empty or nearly empty (<= 12% of box height)
        if (bestSplit < 0 || bestValleyVal > max(1, (box.height * 0.12).toInt())) {
            return null
        }

        val splitX = box.minX + bestSplit
        val leftBox = computeBBoxInRange(mask, w, box.minX, splitX - 1, box.minY, box.maxY) ?: return null
        val rightBox = computeBBoxInRange(mask, w, splitX + 1, box.maxX, box.minY, box.maxY) ?: return null

        val minClusterPixels = max(8, (box.count * 0.18).toInt())
        if (leftBox.count < minClusterPixels || rightBox.count < minClusterPixels) {
            return null
        }

        val leftIsPin = isPinSubShape(mask, w, leftBox, box)
        val rightIsRoad = isRoadSubShape(mask, w, rightBox, box)
        if (leftIsPin && rightIsRoad) {
            return ShapeClassificationResult(
                maneuverCode = BajajProtocol.ARROW_DESTINATION_SOLID,
                confidenceScore = 0.94f,
                label = "dest:pin_left_road_right",
                debugSummary = "split=$bestSplit valley=$bestValleyVal"
            )
        }

        val rightIsPin = isPinSubShape(mask, w, rightBox, box)
        val leftIsRoad = isRoadSubShape(mask, w, leftBox, box)
        if (rightIsPin && leftIsRoad) {
            return ShapeClassificationResult(
                maneuverCode = BajajProtocol.ARROW_DESTINATION_SOLID,
                confidenceScore = 0.94f,
                label = "dest:road_left_pin_right",
                debugSummary = "split=$bestSplit valley=$bestValleyVal"
            )
        }

        return null
    }

    private fun isPinSubShape(mask: BooleanArray, w: Int, sub: BBox, full: BBox): Boolean {
        if (sub.height < full.height * 0.50 || sub.width < 4) return false

        val topSpan = rowSpanAtFraction(mask, w, sub, 0.22)
        val upperMidSpan = rowSpanAtFraction(mask, w, sub, 0.36)
        val botSpan = rowSpanAtFraction(mask, w, sub, 0.90)
        val widestUpper = max(topSpan, upperMidSpan)
        if (widestUpper < sub.width * 0.55) return false

        val tapersToPoint = botSpan <= widestUpper * 0.58
        val hasHole = hasEnclosedHole(mask, w, sub, 0.12, 0.60)
        // A teardrop pin is rounded/wide at top, tapers to a narrow tip at bottom, and usually has a center hole
        return tapersToPoint && (hasHole || (topSpan >= sub.width * 0.50 && botSpan <= widestUpper * 0.42))
    }

    private fun isRoadSubShape(mask: BooleanArray, w: Int, sub: BBox, full: BBox): Boolean {
        if (sub.height < full.height * 0.38 || sub.width < 4) return false
        // Road sits towards the bottom of the icon
        if (sub.maxY < full.maxY - full.height * 0.18) return false

        val topSpan = rowSpanAtFraction(mask, w, sub, 0.12)
        val midSpan = rowSpanAtFraction(mask, w, sub, 0.50)
        val botSpan = rowSpanAtFraction(mask, w, sub, 0.88)

        // Perspective road widens from top to bottom (trapezoid)
        val widensDownward = botSpan >= max(topSpan + 1, (topSpan * 1.25).roundToInt()) &&
            botSpan >= sub.width * 0.68 &&
            midSpan >= topSpan
        return widensDownward
    }

    private fun isSingleTeardropPin(mask: BooleanArray, w: Int, box: BBox): Boolean {
        if (box.height < 8 || box.width < 6) return false
        val aspect = box.width.toDouble() / box.height.toDouble()
        if (aspect < 0.45 || aspect > 1.05) return false

        // Must be horizontally centered / symmetric
        val cx = centroidX(mask, w, box)
        val relCx = abs(cx - box.midX) / (box.width / 2.0)
        if (relCx > 0.22) return false

        val veryTopSpan = rowSpanAtFraction(mask, w, box, 0.12)
        val upperSpan = rowSpanAtFraction(mask, w, box, 0.30)
        val midSpan = rowSpanAtFraction(mask, w, box, 0.52)
        val botSpan = rowSpanAtFraction(mask, w, box, 0.90)

        // Unlike an upward straight arrow (narrow point at 0.12, wide wings at 0.30, constant stem at 0.52..0.90),
        // a pin is rounded/wide near the top (0.12..0.30) and tapers smoothly to a centered point at 0.90
        val roundedTop = veryTopSpan >= upperSpan * 0.55 && upperSpan >= box.width * 0.72
        val taperedBottom = botSpan <= upperSpan * 0.42 && midSpan >= botSpan * 1.3
        if (!roundedTop || !taperedBottom) return false

        // Bottom tip of a centered pin must lie near the horizontal center (rejects U-turn single-side leg)
        val botCx = meanNormXInYRange(
            mask, w, box,
            (box.maxY - box.height * 0.16).toInt().coerceAtLeast(box.minY),
            box.maxY
        ) ?: return false
        if (abs(botCx - 0.5) > 0.18) return false

        // Lower-middle cone of a pin is filled in the center (rejects U-turn two legs with empty gap)
        val coneY0 = box.minY + (box.height * 0.56).toInt()
        val coneY1 = box.minY + (box.height * 0.78).toInt()
        val centerX0 = (box.midX - box.width * 0.12).toInt().coerceAtLeast(box.minX)
        val centerX1 = (box.midX + box.width * 0.12).toInt().coerceAtMost(box.maxX)
        var coneCenterHits = 0
        var coneCenterTotal = 0
        for (gy in coneY0..coneY1) {
            val rowOff = gy * w
            for (gx in centerX0..centerX1) {
                coneCenterTotal++
                if (mask[rowOff + gx]) coneCenterHits++
            }
        }
        if (coneCenterTotal == 0 || coneCenterHits.toDouble() / coneCenterTotal < 0.45) return false

        val hasHole = hasEnclosedHole(mask, w, box, 0.14, 0.56)
        return hasHole || botSpan <= upperSpan * 0.30
    }

    private fun hasEnclosedHole(
        mask: BooleanArray,
        w: Int,
        box: BBox,
        yMinFrac: Double,
        yMaxFrac: Double
    ): Boolean {
        val y0 = (box.minY + box.height * yMinFrac).toInt().coerceIn(box.minY + 1, box.maxY - 1)
        val y1 = (box.minY + box.height * yMaxFrac).toInt().coerceIn(y0, box.maxY - 1)
        val x0 = (box.minX + box.width * 0.22).toInt().coerceIn(box.minX + 1, box.maxX - 1)
        val x1 = (box.maxX - box.width * 0.22).toInt().coerceIn(x0, box.maxX - 1)

        for (gy in y0..y1) {
            for (gx in x0..x1) {
                if (mask[gy * w + gx]) continue
                var hasLeft = false
                var hasRight = false
                var hasUp = false
                var hasDown = false
                for (tx in box.minX until gx) if (mask[gy * w + tx]) { hasLeft = true; break }
                if (!hasLeft) continue
                for (tx in (gx + 1)..box.maxX) if (mask[gy * w + tx]) { hasRight = true; break }
                if (!hasRight) continue
                for (ty in box.minY until gy) if (mask[ty * w + gx]) { hasUp = true; break }
                if (!hasUp) continue
                for (ty in (gy + 1)..box.maxY) if (mask[ty * w + gx]) { hasDown = true; break }
                if (hasDown) return true
            }
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Stage 2: Dedicated U-Turn Detector
    // -------------------------------------------------------------------------

    private fun detectUTurn(
        mask: BooleanArray,
        w: Int,
        box: BBox
    ): ShapeClassificationResult? {
        if (box.height < box.width * 0.85 || box.width < 6 || box.height < 8) return null

        val topY = box.minY + (box.height * 0.26).toInt()
        val lowerY0 = box.minY + (box.height * 0.48).toInt()
        val lowerY1 = box.minY + (box.height * 0.86).toInt()

        // U-turn legs are already wide/parallel by 32% height (unlike a 135° sharp turn which flares out diagonally)
        val spanAt32 = rowSpanAtFraction(mask, w, box, 0.32)
        if (spanAt32 < box.width * 0.62) return null

        // Arch top (0..26% height) must be roughly centered (rejects 135° sharp-turn peak sitting on one edge)
        val topCx = meanNormXInYRange(mask, w, box, box.minY, topY) ?: return null
        if (topCx < 0.32 || topCx > 0.68) return null

        var topSpanCount = 0
        for (gy in box.minY..topY) {
            val rowOff = gy * w
            for (gx in box.minX..box.maxX) {
                if (mask[rowOff + gx]) topSpanCount++
            }
        }
        if (topSpanCount < 6) return null

        var lowerLeft = 0
        var lowerRight = 0
        var lowerCenter = 0
        var maxLeftSpan = 0
        var maxRightSpan = 0

        for (gy in lowerY0..lowerY1) {
            val rowOff = gy * w
            var rowLMin = box.maxX
            var rowLMax = box.minX
            var rowLCount = 0
            var rowRMin = box.maxX
            var rowRMax = box.minX
            var rowRCount = 0

            for (gx in box.minX..box.maxX) {
                if (!mask[rowOff + gx]) continue
                when {
                    gx < box.midX - box.width * 0.12 -> {
                        lowerLeft++
                        rowLCount++
                        if (gx < rowLMin) rowLMin = gx
                        if (gx > rowLMax) rowLMax = gx
                    }
                    gx > box.midX + box.width * 0.12 -> {
                        lowerRight++
                        rowRCount++
                        if (gx < rowRMin) rowRMin = gx
                        if (gx > rowRMax) rowRMax = gx
                    }
                    else -> lowerCenter++
                }
            }
            if (rowLCount > 0) maxLeftSpan = max(maxLeftSpan, rowLMax - rowLMin + 1)
            if (rowRCount > 0) maxRightSpan = max(maxRightSpan, rowRMax - rowRMin + 1)
        }

        if (lowerLeft >= 4 && lowerRight >= 4 &&
            lowerCenter <= min(lowerLeft, lowerRight) * 0.38
        ) {
            // Arrowhead pointing down makes that leg wider/heavier in the lower half
            val isRightUTurn = (maxRightSpan > maxLeftSpan + 1) ||
                (maxRightSpan >= maxLeftSpan && lowerRight > lowerLeft * 1.15)
            val code = if (isRightUTurn) {
                BajajProtocol.ARROW_UTURN_RIGHT_SOLID
            } else {
                BajajProtocol.ARROW_UTURN_LEFT_SOLID
            }
            return ShapeClassificationResult(
                maneuverCode = code,
                confidenceScore = 0.90f,
                label = if (isRightUTurn) "geom:uturn_right" else "geom:uturn_left",
                debugSummary = "lSpan=$maxLeftSpan rSpan=$maxRightSpan lCnt=$lowerLeft rCnt=$lowerRight"
            )
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Stage 3: Dual-Tone Multi-Road Detector (Merge, Fork, Ramp, Keep)
    // -------------------------------------------------------------------------

    private fun detectDualToneManeuver(
        data: IconMaskData,
        fullBox: BBox,
        primBox: BBox,
        secBox: BBox
    ): ShapeClassificationResult? {
        val w = data.width
        val topLimit = fullBox.minY + (fullBox.height * 0.42).toInt()
        val botStart = fullBox.maxY - (fullBox.height * 0.35).toInt()

        val primTopCx = meanNormXInYRange(data.primaryMask, w, fullBox, fullBox.minY, topLimit)
        val primBotCx = meanNormXInYRange(data.primaryMask, w, fullBox, botStart, fullBox.maxY)
        val secTopCx = meanNormXInYRange(data.secondaryMask, w, fullBox, fullBox.minY, topLimit)
        val secBotCx = meanNormXInYRange(data.secondaryMask, w, fullBox, botStart, fullBox.maxY)

        val secTopCount = countInYRange(data.secondaryMask, w, fullBox, fullBox.minY, topLimit)
        val secBotCount = countInYRange(data.secondaryMask, w, fullBox, botStart, fullBox.maxY)

        // 3A. Merge: two separated bottom legs converging to a single top-center arrow
        if (primTopCx != null && primBotCx != null && secBotCx != null &&
            abs(primBotCx - secBotCx) >= 0.22 &&
            abs(primTopCx - 0.5) <= 0.22 &&
            secBotCount > secTopCount * 1.6
        ) {
            return ShapeClassificationResult(
                maneuverCode = BajajProtocol.ARROW_MERGE,
                confidenceScore = 0.88f,
                label = "dual:merge",
                debugSummary = "pBot=${fmt(primBotCx)} sBot=${fmt(secBotCx)} pTop=${fmt(primTopCx)}"
            )
        }

        // 3B. Fork / Ramp / Keep: active route diverges from faded untaken road in upper half
        if (primTopCx != null && secTopCx != null) {
            val deltaTop = primTopCx - secTopCx
            if (abs(deltaTop) >= 0.16) {
                val activeIsRight = deltaTop > 0.0
                val primShift = if (primBotCx != null) abs(primTopCx - primBotCx) else abs(primTopCx - 0.5)
                val secShift = if (secBotCx != null) abs(secTopCx - secBotCx) else abs(secTopCx - 0.5)

                // If both branches diverge symmetrically from bottom center -> FORK (Y-shape)
                val bothDiverge = primShift >= 0.16 && secShift >= 0.16
                if (bothDiverge) {
                    val code = if (activeIsRight) BajajProtocol.ARROW_FORK_RIGHT_SOLID else BajajProtocol.ARROW_FORK_LEFT_SOLID
                    return ShapeClassificationResult(
                        maneuverCode = code,
                        confidenceScore = 0.86f,
                        label = if (activeIsRight) "dual:fork_right" else "dual:fork_left",
                        debugSummary = "dTop=${fmt(deltaTop)} pShift=${fmt(primShift)} sShift=${fmt(secShift)}"
                    )
                }

                // If secondary is a straight vertical road and primary exits to the side -> RAMP or KEEP
                val secIsStraight = secShift < 0.16 && secBox.height >= fullBox.height * 0.55
                if (secIsStraight) {
                    val code = if (primShift >= 0.28) {
                        if (activeIsRight) BajajProtocol.ARROW_RAMP_RIGHT_SOLID else BajajProtocol.ARROW_RAMP_LEFT_SOLID
                    } else {
                        if (activeIsRight) BajajProtocol.ARROW_KEEP_RIGHT_SOLID else BajajProtocol.ARROW_KEEP_LEFT_SOLID
                    }
                    val kind = if (primShift >= 0.28) "ramp" else "keep"
                    return ShapeClassificationResult(
                        maneuverCode = code,
                        confidenceScore = 0.84f,
                        label = "dual:${kind}_${if (activeIsRight) "right" else "left"}",
                        debugSummary = "dTop=${fmt(deltaTop)} pShift=${fmt(primShift)} sShift=${fmt(secShift)}"
                    )
                }

                // If primary goes mostly straight while secondary peels off -> KEEP on primary's side
                val primIsStraight = primShift < 0.18 && primBox.height >= fullBox.height * 0.65
                if (primIsStraight) {
                    val code = if (activeIsRight) BajajProtocol.ARROW_KEEP_RIGHT_SOLID else BajajProtocol.ARROW_KEEP_LEFT_SOLID
                    return ShapeClassificationResult(
                        maneuverCode = code,
                        confidenceScore = 0.82f,
                        label = if (activeIsRight) "dual:keep_right" else "dual:keep_left",
                        debugSummary = "dTop=${fmt(deltaTop)} pShift=${fmt(primShift)} sShift=${fmt(secShift)}"
                    )
                }
            }
        }

        return null
    }

    // -------------------------------------------------------------------------
    // Stage 4: Hybrid 32x32 Template Matching + Arrow Geometry
    // -------------------------------------------------------------------------

    private fun classifySinglePathArrow(
        mask: BooleanArray,
        w: Int,
        h: Int,
        box: BBox
    ): ShapeClassificationResult? {
        val normGrid = normalizeTo32(mask, w, box)
        val normDilated = dilate1(normGrid, NORM_SIZE)

        // Geometric descriptors on the active arrow bounding box
        val baseX = meanNormXInYRange(
            mask, w, box,
            (box.maxY - box.height * 0.18).toInt().coerceAtLeast(box.minY),
            box.maxY
        ) ?: 0.5
        val topX = meanNormXInYRange(
            mask, w, box,
            box.minY,
            (box.minY + box.height * 0.24).toInt().coerceAtMost(box.maxY)
        ) ?: 0.5

        val rightTipY = meanNormYInXRange(
            mask, w, box,
            (box.maxX - box.width * 0.26).toInt().coerceAtLeast(box.minX),
            box.maxX
        )
        val leftTipY = meanNormYInXRange(
            mask, w, box,
            box.minX,
            (box.minX + box.width * 0.26).toInt().coerceAtMost(box.maxX)
        )

        val symmetry = horizontalSymmetry(normGrid, NORM_SIZE)
        val aspect = box.width.toDouble() / box.height.toDouble()
        val dx = topX - baseX

        // Compute geometric prior score per candidate maneuver
        fun geometryScore(code: Byte): Double {
            return when (code) {
                BajajProtocol.ARROW_STRAIGHT_SOLID -> {
                    var s = 0.0
                    if (abs(baseX - 0.5) < 0.16) s += 0.35
                    if (abs(topX - 0.5) < 0.16) s += 0.25
                    if (abs(dx) < 0.15) s += 0.20
                    if (symmetry >= 0.72) s += 0.20
                    if (aspect > 0.90) s -= 0.25
                    s.coerceIn(0.0, 1.0)
                }
                BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID -> {
                    var s = 0.0
                    if (baseX < 0.48) s += 0.30
                    if (dx > 0.20) s += 0.25
                    if (rightTipY != null && rightTipY < 0.36) s += 0.35
                    else if (rightTipY != null && rightTipY > 0.45) s -= 0.30
                    if (symmetry < 0.72) s += 0.10
                    s.coerceIn(0.0, 1.0)
                }
                BajajProtocol.ARROW_SLIGHT_LEFT_SOLID -> {
                    var s = 0.0
                    if (baseX > 0.52) s += 0.30
                    if (dx < -0.20) s += 0.25
                    if (leftTipY != null && leftTipY < 0.36) s += 0.35
                    else if (leftTipY != null && leftTipY > 0.45) s -= 0.30
                    if (symmetry < 0.72) s += 0.10
                    s.coerceIn(0.0, 1.0)
                }
                BajajProtocol.ARROW_RIGHT_SOLID -> {
                    var s = 0.0
                    if (baseX < 0.44) s += 0.30
                    if (rightTipY != null && rightTipY in 0.22..0.54) s += 0.40
                    else if (rightTipY != null && (rightTipY < 0.18 || rightTipY > 0.60)) s -= 0.30
                    if (aspect >= 0.65) s += 0.20
                    if (symmetry < 0.68) s += 0.10
                    s.coerceIn(0.0, 1.0)
                }
                BajajProtocol.ARROW_LEFT_SOLID -> {
                    var s = 0.0
                    if (baseX > 0.56) s += 0.30
                    if (leftTipY != null && leftTipY in 0.22..0.54) s += 0.40
                    else if (leftTipY != null && (leftTipY < 0.18 || leftTipY > 0.60)) s -= 0.30
                    if (aspect >= 0.65) s += 0.20
                    if (symmetry < 0.68) s += 0.10
                    s.coerceIn(0.0, 1.0)
                }
                BajajProtocol.ARROW_SHARP_RIGHT_SOLID -> {
                    var s = 0.0
                    if (baseX < 0.44) s += 0.30
                    if (rightTipY != null && rightTipY >= 0.48) s += 0.50
                    else if (rightTipY != null && rightTipY < 0.38) s -= 0.40
                    if (symmetry < 0.68) s += 0.20
                    s.coerceIn(0.0, 1.0)
                }
                BajajProtocol.ARROW_SHARP_LEFT_SOLID -> {
                    var s = 0.0
                    if (baseX > 0.56) s += 0.30
                    if (leftTipY != null && leftTipY >= 0.48) s += 0.50
                    else if (leftTipY != null && leftTipY < 0.38) s -= 0.40
                    if (symmetry < 0.68) s += 0.20
                    s.coerceIn(0.0, 1.0)
                }
                else -> 0.0
            }
        }

        var bestCode: Byte = 0
        var bestName = ""
        var bestScore = -1.0
        var secondName = ""
        var secondScore = -1.0

        for (tpl in canonicalTemplates) {
            val tplScore = tolerantDice(normGrid, normDilated, tpl.mask, tpl.dilated)
            val geomScore = geometryScore(tpl.code)
            val combined = 0.55 * tplScore + 0.45 * geomScore
            if (combined > bestScore) {
                secondName = bestName
                secondScore = bestScore
                bestScore = combined
                bestCode = tpl.code
                bestName = tpl.name
            } else if (combined > secondScore) {
                secondScore = combined
                secondName = tpl.name
            }
        }

        if (bestScore < 0.54 || bestCode == 0.toByte()) {
            return null
        }

        return ShapeClassificationResult(
            maneuverCode = bestCode,
            confidenceScore = bestScore.toFloat().coerceIn(0f, 1f),
            label = "shape:$bestName",
            debugSummary = "1:$bestName(${fmt(bestScore)}) 2:$secondName(${fmt(secondScore)}) baseX=${fmt(baseX)} topX=${fmt(topX)} rY=${fmt(rightTipY)} lY=${fmt(leftTipY)} sym=${fmt(symmetry)}"
        )
    }

    // -------------------------------------------------------------------------
    // Procedural Canonical 32x32 Reference Templates
    // -------------------------------------------------------------------------

    private fun buildCanonicalTemplates(): List<TemplateEntry> {
        val entries = mutableListOf<TemplateEntry>()

        fun add(code: Byte, name: String, draw: (BooleanArray) -> Unit) {
            val raw = BooleanArray(NORM_SIZE * NORM_SIZE)
            draw(raw)
            // Normalize the template itself through the exact same bounding-box pipeline
            val box = computeBBox(raw, NORM_SIZE, NORM_SIZE) ?: return
            val norm = normalizeTo32(raw, NORM_SIZE, box)
            entries.add(TemplateEntry(code, name, norm, dilate1(norm, NORM_SIZE)))
        }

        // 1. STRAIGHT (↑)
        add(BajajProtocol.ARROW_STRAIGHT_SOLID, "straight") { g ->
            drawCapsule(g, 16.0, 29.0, 16.0, 7.0, 2.2)
            drawArrowHead(g, 16.0, 4.0, 0.0, -1.0, 7.0, 6.0)
        }

        // 2. SLIGHT RIGHT (↗ curved stem on left)
        add(BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID, "slight_right") { g ->
            drawCapsule(g, 10.0, 29.0, 10.0, 17.0, 2.2)
            drawCapsule(g, 10.0, 17.0, 14.0, 12.0, 2.2)
            drawCapsule(g, 14.0, 12.0, 22.0, 7.0, 2.2)
            drawArrowHead(g, 24.0, 6.0, 0.75, -0.66, 6.5, 5.5)
        }

        // 3. SLIGHT LEFT (↖ mirror)
        add(BajajProtocol.ARROW_SLIGHT_LEFT_SOLID, "slight_left") { g ->
            drawCapsule(g, 22.0, 29.0, 22.0, 17.0, 2.2)
            drawCapsule(g, 22.0, 17.0, 18.0, 12.0, 2.2)
            drawCapsule(g, 18.0, 12.0, 10.0, 7.0, 2.2)
            drawArrowHead(g, 8.0, 6.0, -0.75, -0.66, 6.5, 5.5)
        }

        // 4. TURN RIGHT (90° ↱)
        add(BajajProtocol.ARROW_RIGHT_SOLID, "turn_right") { g ->
            drawCapsule(g, 9.0, 29.0, 9.0, 12.0, 2.2)
            drawCapsule(g, 9.0, 12.0, 23.0, 12.0, 2.2)
            drawArrowHead(g, 26.0, 12.0, 1.0, 0.0, 6.5, 5.5)
        }

        // 5. TURN LEFT (90° ↰)
        add(BajajProtocol.ARROW_LEFT_SOLID, "turn_left") { g ->
            drawCapsule(g, 23.0, 29.0, 23.0, 12.0, 2.2)
            drawCapsule(g, 23.0, 12.0, 9.0, 12.0, 2.2)
            drawArrowHead(g, 6.0, 12.0, -1.0, 0.0, 6.5, 5.5)
        }

        // 6. SHARP RIGHT (135° hairpin down-right)
        add(BajajProtocol.ARROW_SHARP_RIGHT_SOLID, "sharp_right") { g ->
            drawCapsule(g, 9.0, 29.0, 9.0, 8.0, 2.2)
            drawCapsule(g, 9.0, 8.0, 13.0, 6.0, 2.2)
            drawCapsule(g, 13.0, 6.0, 23.0, 19.0, 2.2)
            drawArrowHead(g, 25.0, 21.5, 0.65, 0.76, 6.5, 5.5)
        }

        // 7. SHARP LEFT (135° hairpin down-left)
        add(BajajProtocol.ARROW_SHARP_LEFT_SOLID, "sharp_left") { g ->
            drawCapsule(g, 23.0, 29.0, 23.0, 8.0, 2.2)
            drawCapsule(g, 23.0, 8.0, 19.0, 6.0, 2.2)
            drawCapsule(g, 19.0, 6.0, 9.0, 19.0, 2.2)
            drawArrowHead(g, 7.0, 21.5, -0.65, 0.76, 6.5, 5.5)
        }

        return entries
    }

    private fun drawCapsule(
        grid: BooleanArray,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        radius: Double
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val lenSq = dx * dx + dy * dy
        val rSq = radius * radius
        for (py in 0 until NORM_SIZE) {
            for (px in 0 until NORM_SIZE) {
                val t = if (lenSq <= 1e-6) 0.0 else (((px - x0) * dx + (py - y0) * dy) / lenSq).coerceIn(0.0, 1.0)
                val cx = x0 + t * dx
                val cy = y0 + t * dy
                val dSq = (px - cx) * (px - cx) + (py - cy) * (py - cy)
                if (dSq <= rSq) {
                    grid[py * NORM_SIZE + px] = true
                }
            }
        }
    }

    private fun drawArrowHead(
        grid: BooleanArray,
        tipX: Double,
        tipY: Double,
        dirX: Double,
        dirY: Double,
        length: Double,
        halfWidth: Double
    ) {
        val norm = hypot(dirX, dirY).coerceAtLeast(1e-6)
        val ux = dirX / norm
        val uy = dirY / norm
        val px = -uy
        val py = ux

        val baseX = tipX - ux * length
        val baseY = tipY - uy * length
        val leftX = baseX + px * halfWidth
        val leftY = baseY + py * halfWidth
        val rightX = baseX - px * halfWidth
        val rightY = baseY - py * halfWidth

        for (y in 0 until NORM_SIZE) {
            for (x in 0 until NORM_SIZE) {
                if (pointInTriangle(x.toDouble(), y.toDouble(), tipX, tipY, leftX, leftY, rightX, rightY)) {
                    grid[y * NORM_SIZE + x] = true
                }
            }
        }
    }

    private fun pointInTriangle(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
        cx: Double, cy: Double
    ): Boolean {
        fun sign(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double): Double {
            return (x1 - x3) * (y2 - y3) - (x2 - x3) * (y1 - y3)
        }
        val d1 = sign(px, py, ax, ay, bx, by)
        val d2 = sign(px, py, bx, by, cx, cy)
        val d3 = sign(px, py, cx, cy, ax, ay)
        val hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0)
        val hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0)
        return !(hasNeg && hasPos)
    }

    // -------------------------------------------------------------------------
    // Grid & Geometry Utilities
    // -------------------------------------------------------------------------

    private fun computeBBox(mask: BooleanArray, w: Int, h: Int): BBox? {
        return computeBBoxInRange(mask, w, 0, w - 1, 0, h - 1)
    }

    private fun computeBBoxInRange(
        mask: BooleanArray,
        w: Int,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int
    ): BBox? {
        var minX = xEnd + 1
        var maxX = xStart - 1
        var minY = yEnd + 1
        var maxY = yStart - 1
        var count = 0

        for (gy in yStart..yEnd) {
            val rowOff = gy * w
            for (gx in xStart..xEnd) {
                if (mask[rowOff + gx]) {
                    count++
                    if (gx < minX) minX = gx
                    if (gx > maxX) maxX = gx
                    if (gy < minY) minY = gy
                    if (gy > maxY) maxY = gy
                }
            }
        }
        if (count == 0) return null
        return BBox(minX, maxX, minY, maxY, count)
    }

    private fun normalizeTo32(mask: BooleanArray, w: Int, box: BBox): BooleanArray {
        val out = BooleanArray(NORM_SIZE * NORM_SIZE)
        val targetSpan = 28.0
        val maxDim = max(box.width, box.height).toDouble().coerceAtLeast(1.0)
        val scale = targetSpan / maxDim
        val scaledW = box.width * scale
        val scaledH = box.height * scale
        val offX = (NORM_SIZE - scaledW) / 2.0
        val offY = (NORM_SIZE - scaledH) / 2.0

        for (ny in 0 until NORM_SIZE) {
            val sy = box.minY + ((ny + 0.5 - offY) / scale)
            val iy = sy.toInt()
            if (iy < box.minY || iy > box.maxY) continue
            val rowOff = iy * w
            val outOff = ny * NORM_SIZE
            for (nx in 0 until NORM_SIZE) {
                val sx = box.minX + ((nx + 0.5 - offX) / scale)
                val ix = sx.toInt()
                if (ix in box.minX..box.maxX && mask[rowOff + ix]) {
                    out[outOff + nx] = true
                }
            }
        }
        return out
    }

    private fun dilate1(grid: BooleanArray, size: Int): BooleanArray {
        val out = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!grid[y * size + x]) continue
                val y0 = (y - 1).coerceAtLeast(0)
                val y1 = (y + 1).coerceAtMost(size - 1)
                val x0 = (x - 1).coerceAtLeast(0)
                val x1 = (x + 1).coerceAtMost(size - 1)
                for (ny in y0..y1) {
                    val rowOff = ny * size
                    for (nx in x0..x1) {
                        out[rowOff + nx] = true
                    }
                }
            }
        }
        return out
    }

    /**
     * Symmetric 1-pixel-tolerant Dice similarity so slight stroke-width variations
     * between Google Maps versions/DPIs still match strongly.
     */
    private fun tolerantDice(
        a: BooleanArray,
        aDilated: BooleanArray,
        b: BooleanArray,
        bDilated: BooleanArray
    ): Double {
        var countA = 0
        var countB = 0
        var aInB = 0
        var bInA = 0
        for (i in a.indices) {
            if (a[i]) {
                countA++
                if (bDilated[i]) aInB++
            }
            if (b[i]) {
                countB++
                if (aDilated[i]) bInA++
            }
        }
        if (countA == 0 || countB == 0) return 0.0
        return (aInB + bInA).toDouble() / (countA + countB).toDouble()
    }

    private fun horizontalSymmetry(grid: BooleanArray, size: Int): Double {
        var total = 0
        var matched = 0
        for (y in 0 until size) {
            val rowOff = y * size
            for (x in 0 until size) {
                if (grid[rowOff + x]) {
                    total++
                    val mx = size - 1 - x
                    if (grid[rowOff + mx] ||
                        (mx > 0 && grid[rowOff + mx - 1]) ||
                        (mx + 1 < size && grid[rowOff + mx + 1])
                    ) {
                        matched++
                    }
                }
            }
        }
        return if (total == 0) 0.0 else matched.toDouble() / total.toDouble()
    }

    private fun rowSpanAtFraction(mask: BooleanArray, w: Int, box: BBox, yFrac: Double): Int {
        val centerY = (box.minY + (box.height - 1) * yFrac).roundToInt().coerceIn(box.minY, box.maxY)
        val y0 = (centerY - 1).coerceAtLeast(box.minY)
        val y1 = (centerY + 1).coerceAtMost(box.maxY)
        var bestSpan = 0
        for (gy in y0..y1) {
            var rMin = box.maxX + 1
            var rMax = box.minX - 1
            val rowOff = gy * w
            for (gx in box.minX..box.maxX) {
                if (mask[rowOff + gx]) {
                    if (gx < rMin) rMin = gx
                    if (gx > rMax) rMax = gx
                }
            }
            if (rMax >= rMin) {
                bestSpan = max(bestSpan, rMax - rMin + 1)
            }
        }
        return bestSpan
    }

    private fun centroidX(mask: BooleanArray, w: Int, box: BBox): Double {
        var sum = 0L
        var count = 0
        for (gy in box.minY..box.maxY) {
            val rowOff = gy * w
            for (gx in box.minX..box.maxX) {
                if (mask[rowOff + gx]) {
                    sum += gx
                    count++
                }
            }
        }
        return if (count == 0) box.midX else sum.toDouble() / count
    }

    private fun meanNormXInYRange(
        mask: BooleanArray,
        w: Int,
        box: BBox,
        yStart: Int,
        yEnd: Int
    ): Double? {
        val spanX = (box.maxX - box.minX).coerceAtLeast(1)
        var sum = 0L
        var count = 0
        for (gy in yStart..yEnd) {
            val rowOff = gy * w
            for (gx in box.minX..box.maxX) {
                if (mask[rowOff + gx]) {
                    sum += (gx - box.minX)
                    count++
                }
            }
        }
        if (count == 0) return null
        return (sum.toDouble() / count) / spanX
    }

    private fun meanNormYInXRange(
        mask: BooleanArray,
        w: Int,
        box: BBox,
        xStart: Int,
        xEnd: Int
    ): Double? {
        val spanY = (box.maxY - box.minY).coerceAtLeast(1)
        var sum = 0L
        var count = 0
        for (gy in box.minY..box.maxY) {
            val rowOff = gy * w
            for (gx in xStart..xEnd) {
                if (mask[rowOff + gx]) {
                    sum += (gy - box.minY)
                    count++
                }
            }
        }
        if (count == 0) return null
        return (sum.toDouble() / count) / spanY
    }

    private fun countInYRange(
        mask: BooleanArray,
        w: Int,
        box: BBox,
        yStart: Int,
        yEnd: Int
    ): Int {
        var count = 0
        for (gy in yStart..yEnd) {
            val rowOff = gy * w
            for (gx in box.minX..box.maxX) {
                if (mask[rowOff + gx]) count++
            }
        }
        return count
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else String.format(java.util.Locale.US, "%.2f", v)
}

