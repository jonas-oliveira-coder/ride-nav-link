package com.custom.ridenavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class IconShapeClassifierTest {

    private val size = 64

    private fun emptyMask() = BooleanArray(size * size)

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
        for (py in 0 until size) {
            for (px in 0 until size) {
                val t = if (lenSq <= 1e-6) 0.0 else (((px - x0) * dx + (py - y0) * dy) / lenSq).coerceIn(0.0, 1.0)
                val cx = x0 + t * dx
                val cy = y0 + t * dy
                val dSq = (px - cx) * (px - cx) + (py - cy) * (py - cy)
                if (dSq <= rSq) grid[py * size + px] = true
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
        val lx = baseX + px * halfWidth
        val ly = baseY + py * halfWidth
        val rx = baseX - px * halfWidth
        val ry = baseY - py * halfWidth

        fun sign(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) =
            (x1 - x3) * (y2 - y3) - (x2 - x3) * (y1 - y3)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val d1 = sign(x.toDouble(), y.toDouble(), tipX, tipY, lx, ly)
                val d2 = sign(x.toDouble(), y.toDouble(), lx, ly, rx, ry)
                val d3 = sign(x.toDouble(), y.toDouble(), rx, ry, tipX, tipY)
                val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
                val hasPos = d1 > 0 || d2 > 0 || d3 > 0
                if (!(hasNeg && hasPos)) grid[y * size + x] = true
            }
        }
    }

    private fun drawPin(grid: BooleanArray, cx: Double, topY: Double, radius: Double, tipY: Double) {
        val cy = topY + radius
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - cx
                val dy = y - cy
                val d = hypot(dx, dy)
                val inCircle = d <= radius
                val inInnerHole = d <= radius * 0.42
                val inCone = y >= cy && y <= tipY &&
                    kotlin.math.abs(dx) <= radius * (1.0 - (y - cy) / (tipY - cy).coerceAtLeast(1.0))
                if ((inCircle || inCone) && !inInnerHole) {
                    grid[y * size + x] = true
                }
            }
        }
    }

    private fun drawPerspectiveRoad(grid: BooleanArray, cx: Double, topY: Double, botY: Double, topHalfW: Double, botHalfW: Double) {
        for (y in topY.toInt()..botY.toInt()) {
            val t = (y - topY) / (botY - topY).coerceAtLeast(1.0)
            val halfW = topHalfW + t * (botHalfW - topHalfW)
            val dashGap = ((y / 5) % 2 == 0)
            for (x in (cx - halfW).toInt()..(cx + halfW).toInt()) {
                if (x in 0 until size && y in 0 until size) {
                    val inCenterLine = dashGap && kotlin.math.abs(x - cx) <= 1.2
                    if (!inCenterLine) {
                        grid[y * size + x] = true
                    }
                }
            }
        }
    }

    private fun assertClassified(expected: Byte, primary: BooleanArray, secondary: BooleanArray = emptyMask(), msg: String) {
        val res = IconShapeClassifier.classify(IconMaskData(size, size, primary, secondary))
        assertNotNull("$msg: expected ${expected.toInt().toChar()}, got null", res)
        assertEquals(
            "$msg: expected ${expected.toInt().toChar()} but got ${res!!.maneuverCode.toInt().toChar()} (${res.label} ${res.debugSummary})",
            expected,
            res.maneuverCode
        )
    }

    @Test
    fun testStraightArrow() {
        val g = emptyMask()
        drawCapsule(g, 32.0, 56.0, 32.0, 16.0, 3.8)
        drawArrowHead(g, 32.0, 8.0, 0.0, -1.0, 14.0, 11.0)
        assertClassified(BajajProtocol.ARROW_STRAIGHT_SOLID, g, msg = "Straight")
    }

    @Test
    fun testSlightRightScreenshotCase() {
        // Matches the Google Maps curved slight-right arrow in the user's screenshot
        val g = emptyMask()
        drawCapsule(g, 20.0, 56.0, 20.0, 34.0, 3.8)
        drawCapsule(g, 20.0, 34.0, 27.0, 24.0, 3.8)
        drawCapsule(g, 27.0, 24.0, 42.0, 14.0, 3.8)
        drawArrowHead(g, 47.0, 11.0, 0.78, -0.62, 13.0, 10.0)
        assertClassified(BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID, g, msg = "Slight Right")
    }

    @Test
    fun testSlightLeft() {
        val g = emptyMask()
        drawCapsule(g, 44.0, 56.0, 44.0, 34.0, 3.8)
        drawCapsule(g, 44.0, 34.0, 37.0, 24.0, 3.8)
        drawCapsule(g, 37.0, 24.0, 22.0, 14.0, 3.8)
        drawArrowHead(g, 17.0, 11.0, -0.78, -0.62, 13.0, 10.0)
        assertClassified(BajajProtocol.ARROW_SLIGHT_LEFT_SOLID, g, msg = "Slight Left")
    }

    @Test
    fun testTurnRight90() {
        val g = emptyMask()
        drawCapsule(g, 18.0, 56.0, 18.0, 24.0, 3.8)
        drawCapsule(g, 18.0, 24.0, 44.0, 24.0, 3.8)
        drawArrowHead(g, 52.0, 24.0, 1.0, 0.0, 13.0, 10.0)
        assertClassified(BajajProtocol.ARROW_RIGHT_SOLID, g, msg = "Turn Right 90")
    }

    @Test
    fun testTurnLeft90() {
        val g = emptyMask()
        drawCapsule(g, 46.0, 56.0, 46.0, 24.0, 3.8)
        drawCapsule(g, 46.0, 24.0, 20.0, 24.0, 3.8)
        drawArrowHead(g, 12.0, 24.0, -1.0, 0.0, 13.0, 10.0)
        assertClassified(BajajProtocol.ARROW_LEFT_SOLID, g, msg = "Turn Left 90")
    }

    @Test
    fun testSharpRight135() {
        val g = emptyMask()
        drawCapsule(g, 18.0, 56.0, 18.0, 16.0, 3.8)
        drawCapsule(g, 18.0, 16.0, 26.0, 13.0, 3.8)
        drawCapsule(g, 26.0, 13.0, 44.0, 38.0, 3.8)
        drawArrowHead(g, 49.0, 44.0, 0.65, 0.76, 13.0, 10.0)
        assertClassified(BajajProtocol.ARROW_SHARP_RIGHT_SOLID, g, msg = "Sharp Right 135")
    }

    @Test
    fun testSharpLeft135() {
        val g = emptyMask()
        drawCapsule(g, 46.0, 56.0, 46.0, 16.0, 3.8)
        drawCapsule(g, 46.0, 16.0, 38.0, 13.0, 3.8)
        drawCapsule(g, 38.0, 13.0, 20.0, 38.0, 3.8)
        drawArrowHead(g, 15.0, 44.0, -0.65, 0.76, 13.0, 10.0)
        assertClassified(BajajProtocol.ARROW_SHARP_LEFT_SOLID, g, msg = "Sharp Left 135")
    }

    @Test
    fun testUTurnLeftAndRight() {
        // U-Turn Left: right leg up, arch over top, left leg down with arrowhead
        val left = emptyMask()
        drawCapsule(left, 44.0, 56.0, 44.0, 18.0, 3.5)
        drawCapsule(left, 44.0, 18.0, 32.0, 10.0, 3.5)
        drawCapsule(left, 32.0, 10.0, 20.0, 18.0, 3.5)
        drawCapsule(left, 20.0, 18.0, 20.0, 42.0, 3.5)
        drawArrowHead(left, 20.0, 50.0, 0.0, 1.0, 12.0, 9.0)
        assertClassified(BajajProtocol.ARROW_UTURN_LEFT_SOLID, left, msg = "U-Turn Left")

        // U-Turn Right: left leg up, arch over top, right leg down with arrowhead
        val right = emptyMask()
        drawCapsule(right, 20.0, 56.0, 20.0, 18.0, 3.5)
        drawCapsule(right, 20.0, 18.0, 32.0, 10.0, 3.5)
        drawCapsule(right, 32.0, 10.0, 44.0, 18.0, 3.5)
        drawCapsule(right, 44.0, 18.0, 44.0, 42.0, 3.5)
        drawArrowHead(right, 44.0, 50.0, 0.0, 1.0, 12.0, 9.0)
        assertClassified(BajajProtocol.ARROW_UTURN_RIGHT_SOLID, right, msg = "U-Turn Right")
    }

    @Test
    fun testDestinationPinLeftRoadRightScreenshotCase() {
        // Matches the exact Google Maps arrive_left icon in the user's screenshot
        val g = emptyMask()
        drawPin(g, cx = 18.0, topY = 10.0, radius = 10.0, tipY = 48.0)
        drawPerspectiveRoad(g, cx = 44.0, topY = 24.0, botY = 52.0, topHalfW = 4.5, botHalfW = 12.0)
        assertTrue(IconShapeClassifier.looksLikeDestination(g, size, size))
        assertClassified(BajajProtocol.ARROW_DESTINATION_SOLID, g, msg = "Destination Pin-Left Road-Right")
    }

    @Test
    fun testDestinationPinAndRoadWithHorizontalOverlap() {
        // Pin right shoulder (x up to 31) overlaps horizontally with Road bottom-left (x down to 28)
        // but they are still separate 2D connected components (just like Google Maps dark mode icon)
        val g = emptyMask()
        drawPin(g, cx = 20.0, topY = 8.0, radius = 11.0, tipY = 44.0)
        drawPerspectiveRoad(g, cx = 42.0, topY = 26.0, botY = 54.0, topHalfW = 4.5, botHalfW = 14.0)
        assertTrue(IconShapeClassifier.looksLikeDestination(g, size, size))
        assertClassified(BajajProtocol.ARROW_DESTINATION_SOLID, g, msg = "Destination X-Overlapping Pin+Road")
    }

    @Test
    fun testDestinationRoadLeftPinRight() {
        val g = emptyMask()
        drawPerspectiveRoad(g, cx = 20.0, topY = 24.0, botY = 52.0, topHalfW = 4.5, botHalfW = 12.0)
        drawPin(g, cx = 46.0, topY = 10.0, radius = 10.0, tipY = 48.0)
        assertTrue(IconShapeClassifier.looksLikeDestination(g, size, size))
        assertClassified(BajajProtocol.ARROW_DESTINATION_SOLID, g, msg = "Destination Road-Left Pin-Right")
    }

    @Test
    fun testDestinationSingleCenteredPin() {
        val g = emptyMask()
        drawPin(g, cx = 32.0, topY = 8.0, radius = 13.0, tipY = 56.0)
        assertTrue(IconShapeClassifier.looksLikeDestination(g, size, size))
        assertClassified(BajajProtocol.ARROW_DESTINATION_SOLID, g, msg = "Destination Centered Pin")
    }

    @Test
    fun testRoundaboutIsNotMistakenForDestination() {
        // Draw a roundabout ring + bottom stem + top-right exit
        val g = emptyMask()
        val cx = 32.0
        val cy = 32.0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val d = hypot(x - cx, y - cy)
                if (d in 10.0..15.0) g[y * size + x] = true
            }
        }
        drawCapsule(g, 32.0, 45.0, 32.0, 58.0, 3.0)
        drawCapsule(g, 42.0, 22.0, 54.0, 12.0, 3.0)
        assertFalse(IconShapeClassifier.looksLikeDestination(g, size, size))
    }

    @Test
    fun testDualToneMerge() {
        val primary = emptyMask()
        val secondary = emptyMask()
        drawCapsule(primary, 20.0, 56.0, 32.0, 34.0, 3.5)
        drawCapsule(primary, 32.0, 34.0, 32.0, 14.0, 3.5)
        drawArrowHead(primary, 32.0, 8.0, 0.0, -1.0, 12.0, 9.0)
        drawCapsule(secondary, 44.0, 56.0, 34.0, 36.0, 3.5)
        assertClassified(BajajProtocol.ARROW_MERGE, primary, secondary, msg = "Dual-Tone Merge")
    }

    @Test
    fun testDualToneForkLeftAndRight() {
        val primR = emptyMask()
        val secR = emptyMask()
        drawCapsule(primR, 32.0, 56.0, 32.0, 36.0, 3.5)
        drawCapsule(primR, 32.0, 36.0, 48.0, 16.0, 3.5)
        drawArrowHead(primR, 52.0, 11.0, 0.65, -0.76, 11.0, 8.0)
        drawCapsule(secR, 30.0, 36.0, 14.0, 14.0, 3.5)
        assertClassified(BajajProtocol.ARROW_FORK_RIGHT_SOLID, primR, secR, msg = "Dual-Tone Fork Right")

        val primL = emptyMask()
        val secL = emptyMask()
        drawCapsule(primL, 32.0, 56.0, 32.0, 36.0, 3.5)
        drawCapsule(primL, 32.0, 36.0, 16.0, 16.0, 3.5)
        drawArrowHead(primL, 12.0, 11.0, -0.65, -0.76, 11.0, 8.0)
        drawCapsule(secL, 34.0, 36.0, 50.0, 14.0, 3.5)
        assertClassified(BajajProtocol.ARROW_FORK_LEFT_SOLID, primL, secL, msg = "Dual-Tone Fork Left")
    }

    @Test
    fun testDualToneRampRightAndKeepRight() {
        // Ramp Right: straight faded vertical road on left + wide curved exit to right
        val primRamp = emptyMask()
        val secRamp = emptyMask()
        drawCapsule(secRamp, 18.0, 56.0, 18.0, 10.0, 3.2)
        drawCapsule(primRamp, 18.0, 56.0, 18.0, 36.0, 3.5)
        drawCapsule(primRamp, 18.0, 36.0, 46.0, 14.0, 3.5)
        drawArrowHead(primRamp, 52.0, 10.0, 0.78, -0.62, 11.0, 8.0)
        assertClassified(BajajProtocol.ARROW_RAMP_RIGHT_SOLID, primRamp, secRamp, msg = "Dual-Tone Ramp Right")
    }
}

