package com.custom.ridenavlink

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class ParsedIconManeuver(
    val maneuverCode: Byte?,
    val roundaboutExit: Int = 0,
    val isRoundabout: Boolean = false,
    val confidence: String = "none"
)

object GoogleMapsIconParser {

    private const val TAG = "MapsIconParser"

    fun extractManeuverFromNotification(
        notification: Notification,
        extras: Bundle,
        context: Context,
        counterClockwise: Boolean = true
    ): ParsedIconManeuver? {
        val smallIcon: Icon? = notification.smallIcon
        val largeIcon: Icon? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notification.getLargeIcon()
                ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG)
                ?: extras.getParcelable("android.largeIcon")
        } else null

        // 1. Decode via icon resource name (largeIcon first, as smallIcon is often generic app logo)
        for (icon in listOfNotNull(largeIcon, smallIcon)) {
            parseResourceName(icon, context, counterClockwise)?.let { parsed ->
                Log.d(TAG, "Mapeado por recurso: code=${parsed.maneuverCode} exit=${parsed.roundaboutExit} info=${parsed.confidence}")
                return parsed
            }
        }

        // 2. Visual bitmap analysis (arrow geometry, roundabout ring, and exit angle)
        val largeBitmap: Bitmap? = extractBitmapFromNotification(notification, extras, context)

        if (largeBitmap != null) {
            parseBitmapShape(largeBitmap, counterClockwise)?.let { parsed ->
                Log.d(TAG, "Mapeado por bitmap: code=${parsed.maneuverCode} exit=${parsed.roundaboutExit} info=${parsed.confidence}")
                return parsed
            }
        }

        return null
    }

    private fun parseResourceName(
        icon: Icon,
        context: Context,
        counterClockwise: Boolean
    ): ParsedIconManeuver? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (icon.type != Icon.TYPE_RESOURCE) return null

        val resId = icon.resId
        val resPackage = icon.resPackage.ifBlank { "com.google.android.apps.maps" }
        if (resId == 0) return null

        val entryName = runCatching {
            val res = context.packageManager.getResourcesForApplication(resPackage)
            res.getResourceEntryName(resId).lowercase(Locale.US)
        }.getOrNull() ?: return null

        Log.d(TAG, "Resource entry name: $entryName")

        val isRoundabout = entryName.contains("roundabout") || entryName.contains("rotary")
        if (isRoundabout) {
            val exit = when {
                entryName.contains("1") || entryName.contains("first") -> 1
                entryName.contains("2") || entryName.contains("second") -> 2
                entryName.contains("3") || entryName.contains("third") -> 3
                entryName.contains("4") || entryName.contains("fourth") -> 4
                entryName.contains("5") || entryName.contains("fifth") -> 5
                entryName.contains("6") || entryName.contains("sixth") -> 6
                entryName.contains("7") || entryName.contains("seventh") -> 7
                else -> 0
            }
            val explicitClockwise = entryName.contains("cw") || entryName.contains("horario")
            val code = if (explicitClockwise || !counterClockwise) {
                BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID
            } else {
                BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID
            }
            return ParsedIconManeuver(
                maneuverCode = code,
                roundaboutExit = exit,
                isRoundabout = true,
                confidence = "res:$entryName"
            )
        }

        val code = when {
            entryName.contains("sharp_right") || entryName.contains("sharp_right_turn") -> BajajProtocol.ARROW_SHARP_RIGHT_SOLID
            entryName.contains("slight_right") -> BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID
            entryName.contains("turn_right") || entryName.contains("right_turn") || entryName.contains("right") -> BajajProtocol.ARROW_RIGHT_SOLID
            entryName.contains("sharp_left") || entryName.contains("sharp_left_turn") -> BajajProtocol.ARROW_SHARP_LEFT_SOLID
            entryName.contains("slight_left") -> BajajProtocol.ARROW_SLIGHT_LEFT_SOLID
            entryName.contains("turn_left") || entryName.contains("left_turn") || entryName.contains("left") -> BajajProtocol.ARROW_LEFT_SOLID
            entryName.contains("straight") || entryName.contains("forward") || entryName.contains("continue") -> BajajProtocol.ARROW_STRAIGHT_SOLID
            entryName.contains("u_turn") || entryName.contains("uturn") -> BajajProtocol.ARROW_UTURN_LEFT_SOLID
            entryName.contains("fork_left") -> BajajProtocol.ARROW_FORK_LEFT_SOLID
            entryName.contains("fork_right") || entryName.contains("fork") -> BajajProtocol.ARROW_FORK_RIGHT_SOLID
            entryName.contains("ramp_left") -> BajajProtocol.ARROW_RAMP_LEFT_SOLID
            entryName.contains("ramp_right") || entryName.contains("ramp") -> BajajProtocol.ARROW_RAMP_RIGHT_SOLID
            entryName.contains("merge") -> BajajProtocol.ARROW_MERGE
            entryName.contains("destination") || entryName.contains("flag") || entryName.contains("arrive") -> BajajProtocol.ARROW_DESTINATION_SOLID
            else -> null
        }

        return if (code != null) {
            ParsedIconManeuver(maneuverCode = code, confidence = "res:$entryName")
        } else null
    }

    private fun extractBitmapFromNotification(
        notification: Notification,
        extras: Bundle,
        context: Context
    ): Bitmap? {
        val largeIcon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notification.getLargeIcon()
        } else null
        val smallIcon = notification.smallIcon

        // Attempt to fetch as Bitmap with protection against ClassCastException (on Android 10+ extras may hold Icon)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val bmp = extras.getParcelable(Notification.EXTRA_LARGE_ICON, Bitmap::class.java)
                    ?: extras.getParcelable("android.largeIcon", Bitmap::class.java)
                if (bmp != null) return bmp

                val ic = extras.getParcelable(Notification.EXTRA_LARGE_ICON, Icon::class.java)
                    ?: extras.getParcelable("android.largeIcon", Icon::class.java)
                if (ic != null) {
                    val bmpFromIcon = extractBitmapFromIcon(ic, context)
                    if (bmpFromIcon != null) return bmpFromIcon
                }
            } else {
                @Suppress("DEPRECATION")
                val parcelable = extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON)
                    ?: extras.getParcelable<android.os.Parcelable>("android.largeIcon")
                if (parcelable is Bitmap) return parcelable
                if (parcelable is Icon) {
                    val bmpFromIcon = extractBitmapFromIcon(parcelable, context)
                    if (bmpFromIcon != null) return bmpFromIcon
                }
            }
        } catch (_: Exception) {
            // Ignore notification extras cast failures
        }

        return extractBitmapFromIcon(largeIcon, context) ?: extractBitmapFromIcon(smallIcon, context)
    }

    private fun extractBitmapFromIcon(icon: Icon?, context: Context): Bitmap? {
        if (icon == null) return null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val w = drawable.intrinsicWidth.takeIf { it in 8..512 } ?: 64
                val h = drawable.intrinsicHeight.takeIf { it in 8..512 } ?: 64
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBitmapShape(
        bitmap: Bitmap,
        counterClockwise: Boolean
    ): ParsedIconManeuver? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return null

        val step = when {
            w > 192 -> 3
            w > 96 -> 2
            else -> 1
        }

        val gridW = (w + step - 1) / step
        val gridH = (h + step - 1) / step
        val alphaMask = BooleanArray(gridW * gridH)
        var alphaCount = 0
        var totalSampled = 0

        for (gy in 0 until gridH) {
            val y = gy * step
            for (gx in 0 until gridW) {
                val x = gx * step
                totalSampled++
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha > 40) {
                    alphaMask[gy * gridW + gx] = true
                    alphaCount++
                }
            }
        }

        if (alphaCount < 15) return null

        // If icon has an opaque background (adaptive/framed icon), extract high-contrast foreground by luminance
        val fgMask = if (totalSampled > 0 && alphaCount > totalSampled * 0.72) {
            extractContrastForeground(bitmap, step, gridW, gridH) ?: return null
        } else {
            alphaMask
        }

        var sumX = 0L
        var sumY = 0L
        var count = 0
        var minX = gridW
        var maxX = 0
        var minY = gridH
        var maxY = 0

        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                if (fgMask[gy * gridW + gx]) {
                    sumX += gx
                    sumY += gy
                    count++
                    if (gx < minX) minX = gx
                    if (gx > maxX) maxX = gx
                    if (gy < minY) minY = gy
                    if (gy > maxY) maxY = gy
                }
            }
        }

        if (count < 12) return null

        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        if (boxW < 4 || boxH < 4) return null

        val cx = sumX.toDouble() / count
        val cy = sumY.toDouble() / count
        val midX = (minX + maxX) / 2.0
        val midY = (minY + maxY) / 2.0

        // 1. Roundabout ring search: handles off-center rings caused by side exits or bottom entry stems
        detectRoundaboutFromMask(fgMask, gridW, minX, maxX, minY, maxY, counterClockwise)?.let {
            return it
        }

        val relCx = (cx - midX) / (boxW / 2.0)

        // 2. Check U-turn (tall arch at top + two vertical legs on left and right)
        detectUTurnFromMask(fgMask, gridW, minX, maxX, minY, maxY, cx, cy)?.let {
            return it
        }

        // 3. Directional left/right analysis using arrowhead tip Y position (avoids confusing 90° turns with sharp turns)
        var leftPixels = 0
        var rightPixels = 0
        val leftBound = minX + boxW * 0.32
        val rightBound = maxX - boxW * 0.32

        for (gy in minY..maxY) {
            for (gx in minX..maxX) {
                if (!fgMask[gy * gridW + gx]) continue
                if (gx <= leftBound) leftPixels++
                if (gx >= rightBound) rightPixels++
            }
        }

        if (rightPixels > leftPixels * 1.35) {
            val tipStartX = (maxX - boxW * 0.28).toInt().coerceIn(minX, maxX)
            val tipAvgNormY = averageNormYInXRange(fgMask, gridW, tipStartX, maxX, minY, maxY)
            val code = when {
                tipAvgNormY != null && tipAvgNormY < 0.30 -> BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID
                tipAvgNormY != null && tipAvgNormY > 0.58 -> BajajProtocol.ARROW_SHARP_RIGHT_SOLID
                else -> BajajProtocol.ARROW_RIGHT_SOLID
            }
            return ParsedIconManeuver(maneuverCode = code, confidence = "bitmap:right")
        }

        if (leftPixels > rightPixels * 1.35) {
            val tipEndX = (minX + boxW * 0.28).toInt().coerceIn(minX, maxX)
            val tipAvgNormY = averageNormYInXRange(fgMask, gridW, minX, tipEndX, minY, maxY)
            val code = when {
                tipAvgNormY != null && tipAvgNormY < 0.30 -> BajajProtocol.ARROW_SLIGHT_LEFT_SOLID
                tipAvgNormY != null && tipAvgNormY > 0.58 -> BajajProtocol.ARROW_SHARP_LEFT_SOLID
                else -> BajajProtocol.ARROW_LEFT_SOLID
            }
            return ParsedIconManeuver(maneuverCode = code, confidence = "bitmap:left")
        }

        // 4. Symmetric vertical shapes: distinguish Destination Pin vs Straight Arrow
        if (abs(relCx) < 0.28) {
            if (isDestinationPinShape(fgMask, gridW, minX, maxX, minY, maxY)) {
                return ParsedIconManeuver(
                    maneuverCode = BajajProtocol.ARROW_DESTINATION_SOLID,
                    confidence = "bitmap:destination"
                )
            }
            val topZone = minY + (boxH * 0.32)
            val bottomZone = maxY - (boxH * 0.32)
            var topPixels = 0
            var bottomPixels = 0
            for (gy in minY..maxY) {
                for (gx in minX..maxX) {
                    if (!fgMask[gy * gridW + gx]) continue
                    if (gy <= topZone) topPixels++
                    if (gy >= bottomZone) bottomPixels++
                }
            }
            if (topPixels > bottomPixels * 1.2) {
                return ParsedIconManeuver(
                    maneuverCode = BajajProtocol.ARROW_STRAIGHT_SOLID,
                    confidence = "bitmap:straight"
                )
            }
        }

        return null
    }

    private fun extractContrastForeground(
        bitmap: Bitmap,
        step: Int,
        gridW: Int,
        gridH: Int
    ): BooleanArray? {
        val edgeLumas = mutableListOf<Int>()
        for (gx in 0 until gridW) {
            edgeLumas.add(pixelLuma(bitmap.getPixel(gx * step, 0)))
            edgeLumas.add(pixelLuma(bitmap.getPixel(gx * step, (gridH - 1) * step)))
        }
        val bgLuma = edgeLumas.sorted()[edgeLumas.size / 2]
        val mask = BooleanArray(gridW * gridH)
        var fgCount = 0
        for (gy in 0 until gridH) {
            val y = gy * step
            for (gx in 0 until gridW) {
                val pixel = bitmap.getPixel(gx * step, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha > 40 && abs(pixelLuma(pixel) - bgLuma) >= 55) {
                    mask[gy * gridW + gx] = true
                    fgCount++
                }
            }
        }
        val total = gridW * gridH
        return if (fgCount in 15..(total * 0.65).toInt()) mask else null
    }

    private fun pixelLuma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun detectRoundaboutFromMask(
        fgMask: BooleanArray,
        gridW: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        counterClockwise: Boolean
    ): ParsedIconManeuver? {
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        val baseSize = minOf(boxW, boxH).toDouble()
        if (baseSize < 8.0) return null

        val midX = (minX + maxX) / 2.0
        val midY = (minY + maxY) / 2.0

        var bestScore = -1.0
        var bestCx = midX
        var bestCy = midY
        var bestR = baseSize * 0.28

        val centerOffsets = doubleArrayOf(-0.18, -0.09, 0.0, 0.09, 0.18)
        val radiusFractions = doubleArrayOf(0.22, 0.28, 0.34)

        for (ox in centerOffsets) {
            val candCx = midX + ox * boxW
            for (oy in centerOffsets) {
                val candCy = midY + oy * boxH
                for (rf in radiusFractions) {
                    val r = baseSize * rf
                    val innerR = r * 0.52
                    val ringMinR = r * 0.72
                    val ringMaxR = r * 1.28

                    var coreHit = 0
                    var coreTotal = 0
                    var ringHit = 0
                    val octantHits = IntArray(8)

                    val y0 = (candCy - ringMaxR).toInt().coerceAtLeast(minY)
                    val y1 = (candCy + ringMaxR).toInt().coerceAtMost(maxY)
                    val x0 = (candCx - ringMaxR).toInt().coerceAtLeast(minX)
                    val x1 = (candCx + ringMaxR).toInt().coerceAtMost(maxX)

                    for (gy in y0..y1) {
                        for (gx in x0..x1) {
                            val dx = gx - candCx
                            val dy = gy - candCy
                            val dist = sqrt(dx * dx + dy * dy)
                            val active = fgMask[gy * gridW + gx]
                            if (dist <= innerR) {
                                coreTotal++
                                if (active) coreHit++
                            } else if (dist in ringMinR..ringMaxR && active) {
                                ringHit++
                                val angle = (Math.toDegrees(atan2(dx, -dy)) + 360.0) % 360.0
                                val oct = (angle / 45.0).toInt().coerceIn(0, 7)
                                octantHits[oct]++
                            }
                        }
                    }

                    if (coreTotal == 0 || ringHit < 14) continue
                    val coreDensity = coreHit.toDouble() / coreTotal
                    val coveredOctants = octantHits.count { it >= 2 }
                    if (coreDensity <= 0.22 && coveredOctants >= 6) {
                        val score = coveredOctants * 100.0 + ringHit - coreHit * 3.0
                        if (score > bestScore) {
                            bestScore = score
                            bestCx = candCx
                            bestCy = candCy
                            bestR = r
                        }
                    }
                }
            }
        }

        if (bestScore < 0.0) return null

        val exitMinR = bestR * 1.35
        var exitTop = 0
        var exitRight = 0
        var exitBottom = 0
        var exitLeft = 0

        for (gy in minY..maxY) {
            for (gx in minX..maxX) {
                if (!fgMask[gy * gridW + gx]) continue
                val dx = gx - bestCx
                val dy = gy - bestCy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > exitMinR) {
                    val angleDeg = (Math.toDegrees(atan2(dx, -dy)) + 360.0) % 360.0
                    // Exclude the vertical entry stem directly below the ring (165..195 deg)
                    when (angleDeg) {
                        in 315.0..360.0, in 0.0..45.0 -> exitTop++
                        in 45.0..135.0 -> exitRight++
                        in 135.0..165.0, in 195.0..225.0 -> exitBottom++
                        in 225.0..315.0 -> exitLeft++
                    }
                }
            }
        }

        val maxExit = maxOf(exitTop, exitRight, exitBottom, exitLeft)
        val inferredExit = if (maxExit < 3) {
            2
        } else if (counterClockwise) {
            when (maxExit) {
                exitRight -> 1
                exitTop -> 2
                exitLeft -> 3
                else -> 4
            }
        } else {
            when (maxExit) {
                exitLeft -> 1
                exitTop -> 2
                exitRight -> 3
                else -> 4
            }
        }

        val code = if (counterClockwise) {
            BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID
        } else {
            BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID
        }

        return ParsedIconManeuver(
            maneuverCode = code,
            roundaboutExit = inferredExit,
            isRoundabout = true,
            confidence = "bitmap:roundabout:exit$inferredExit"
        )
    }

    private fun detectUTurnFromMask(
        fgMask: BooleanArray,
        gridW: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        cx: Double,
        cy: Double
    ): ParsedIconManeuver? {
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        if (boxH < boxW * 0.9) return null

        val midX = (minX + maxX) / 2.0
        val topY = minY + (boxH * 0.25).toInt()
        val lowerY0 = minY + (boxH * 0.50).toInt()
        val lowerY1 = minY + (boxH * 0.85).toInt()

        var topSpanCount = 0
        var lowerLeftCount = 0
        var lowerRightCount = 0
        var lowerCenterCount = 0

        for (gy in minY..topY) {
            for (gx in minX..maxX) {
                if (fgMask[gy * gridW + gx]) topSpanCount++
            }
        }
        for (gy in lowerY0..lowerY1) {
            for (gx in minX..maxX) {
                if (!fgMask[gy * gridW + gx]) continue
                when {
                    gx < midX - boxW * 0.15 -> lowerLeftCount++
                    gx > midX + boxW * 0.15 -> lowerRightCount++
                    else -> lowerCenterCount++
                }
            }
        }

        // U-turn has an arch across the top, two distinct vertical legs below, and an empty gap between them
        if (topSpanCount >= 6 && lowerLeftCount >= 4 && lowerRightCount >= 4 &&
            lowerCenterCount <= minOf(lowerLeftCount, lowerRightCount) * 0.5
        ) {
            val code = if (cx < midX && cy > minY + boxH * 0.4) {
                BajajProtocol.ARROW_UTURN_RIGHT_SOLID
            } else {
                BajajProtocol.ARROW_UTURN_LEFT_SOLID
            }
            return ParsedIconManeuver(maneuverCode = code, confidence = "bitmap:uturn")
        }
        return null
    }

    private fun averageNormYInXRange(
        fgMask: BooleanArray,
        gridW: Int,
        xStart: Int,
        xEnd: Int,
        minY: Int,
        maxY: Int
    ): Double? {
        val boxH = (maxY - minY).coerceAtLeast(1)
        var sumY = 0L
        var count = 0
        for (gy in minY..maxY) {
            for (gx in xStart..xEnd) {
                if (fgMask[gy * gridW + gx]) {
                    sumY += (gy - minY)
                    count++
                }
            }
        }
        if (count == 0) return null
        return (sumY.toDouble() / count) / boxH
    }

    private fun isDestinationPinShape(
        fgMask: BooleanArray,
        gridW: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int
    ): Boolean {
        val boxH = maxY - minY + 1
        val boxW = maxX - minX + 1
        if (boxH < 6 || boxW < 5) return false

        val topRowY = minY + (boxH * 0.18).toInt()
        val midRowY = minY + (boxH * 0.45).toInt()
        val botRowY = maxY - (boxH * 0.12).toInt().coerceAtLeast(1)

        var topMinX = maxX
        var topMaxX = minX
        var topCount = 0
        var midMinX = maxX
        var midMaxX = minX
        var botMinX = maxX
        var botMaxX = minX
        var botCount = 0

        for (gx in minX..maxX) {
            if (fgMask[topRowY * gridW + gx]) {
                if (gx < topMinX) topMinX = gx
                if (gx > topMaxX) topMaxX = gx
                topCount++
            }
            if (fgMask[midRowY * gridW + gx]) {
                if (gx < midMinX) midMinX = gx
                if (gx > midMaxX) midMaxX = gx
            }
            if (fgMask[botRowY * gridW + gx]) {
                if (gx < botMinX) botMinX = gx
                if (gx > botMaxX) botMaxX = gx
                botCount++
            }
        }

        if (topCount == 0 || botCount == 0) return false
        val topSpan = topMaxX - topMinX + 1
        val midSpan = midMaxX - midMinX + 1
        val botSpan = botMaxX - botMinX + 1

        // A pin is wide near the top (rounded head) and tapers to a narrow point at the bottom
        return topSpan >= boxW * 0.45 && midSpan >= boxW * 0.45 && botSpan <= topSpan * 0.55
    }
}
