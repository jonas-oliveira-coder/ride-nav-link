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
    val confidence: String = "none",
    val confidenceScore: Float = 0f
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
                entryName.contains("1") || entryName.contains("first") -> 2
                entryName.contains("2") || entryName.contains("second") -> 4
                entryName.contains("3") || entryName.contains("third") -> 6
                entryName.contains("4") || entryName.contains("fourth") -> 7
                entryName.contains("5") || entryName.contains("fifth") -> 7
                entryName.contains("6") || entryName.contains("sixth") -> 7
                entryName.contains("7") || entryName.contains("seventh") -> 7
                else -> 0
            }
            val explicitClockwise = entryName.contains("cw") || entryName.contains("horario")
            val code = if (explicitClockwise || !counterClockwise) {
                BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID
            } else {
                BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID
            }
            return ParsedIconManeuver(
                maneuverCode = code,
                roundaboutExit = exit,
                isRoundabout = true,
                confidence = "res:$entryName",
                confidenceScore = 1.0f
            )
        }

        val code = when {
            entryName.contains("destination") || entryName.contains("flag") || entryName.contains("arrive") -> BajajProtocol.ARROW_DESTINATION_SOLID
            entryName.contains("ferry_train") || entryName.contains("train") -> BajajProtocol.ARROW_FERRY_TRAIN_SOLID
            entryName.contains("ferry") || entryName.contains("boat") -> BajajProtocol.ARROW_FERRY_SOLID
            entryName.contains("u_turn_right") || entryName.contains("uturn_right") -> BajajProtocol.ARROW_UTURN_RIGHT_SOLID
            entryName.contains("u_turn") || entryName.contains("uturn") -> BajajProtocol.ARROW_UTURN_LEFT_SOLID
            entryName.contains("sharp_right") || entryName.contains("sharp_right_turn") -> BajajProtocol.ARROW_SHARP_RIGHT_SOLID
            entryName.contains("slight_right") -> BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID
            entryName.contains("keep_right") -> BajajProtocol.ARROW_KEEP_RIGHT_SOLID
            entryName.contains("fork_right") -> BajajProtocol.ARROW_FORK_RIGHT_SOLID
            entryName.contains("ramp_right") -> BajajProtocol.ARROW_RAMP_RIGHT_SOLID
            entryName.contains("turn_right") || entryName.contains("right_turn") || entryName.contains("right") -> BajajProtocol.ARROW_RIGHT_SOLID
            entryName.contains("sharp_left") || entryName.contains("sharp_left_turn") -> BajajProtocol.ARROW_SHARP_LEFT_SOLID
            entryName.contains("slight_left") -> BajajProtocol.ARROW_SLIGHT_LEFT_SOLID
            entryName.contains("keep_left") -> BajajProtocol.ARROW_KEEP_LEFT_SOLID
            entryName.contains("fork_left") -> BajajProtocol.ARROW_FORK_LEFT_SOLID
            entryName.contains("ramp_left") -> BajajProtocol.ARROW_RAMP_LEFT_SOLID
            entryName.contains("turn_left") || entryName.contains("left_turn") || entryName.contains("left") -> BajajProtocol.ARROW_LEFT_SOLID
            entryName.contains("fork") -> BajajProtocol.ARROW_FORK_RIGHT_SOLID
            entryName.contains("ramp") -> BajajProtocol.ARROW_RAMP_RIGHT_SOLID
            entryName.contains("merge") -> BajajProtocol.ARROW_MERGE
            entryName.contains("straight") || entryName.contains("forward") || entryName.contains("continue") -> BajajProtocol.ARROW_STRAIGHT_SOLID
            else -> null
        }

        return if (code != null) {
            ParsedIconManeuver(maneuverCode = code, confidence = "res:$entryName", confidenceScore = 1.0f)
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
        val primaryAlphaMask = BooleanArray(gridW * gridH)
        val secondaryAlphaMask = BooleanArray(gridW * gridH)
        var alphaCount = 0
        var primaryAlphaCount = 0
        var totalSampled = 0

        for (gy in 0 until gridH) {
            val y = gy * step
            for (gx in 0 until gridW) {
                val x = gx * step
                totalSampled++
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                val idx = gy * gridW + gx
                if (alpha >= 35) {
                    alphaMask[idx] = true
                    alphaCount++
                    if (alpha >= 165) {
                        primaryAlphaMask[idx] = true
                        primaryAlphaCount++
                    } else {
                        secondaryAlphaMask[idx] = true
                    }
                }
            }
        }

        if (alphaCount < 15) return null

        // Extract dual-tone masks:
        // - primaryMask: solid active route arrow
        // - secondaryMask: faded/dimmed untaken road (Fork, Ramp, Keep, Merge)
        val maskData: IconMaskData = if (totalSampled > 0 && alphaCount > totalSampled * 0.72) {
            extractContrastDualMasks(bitmap, step, gridW, gridH) ?: return null
        } else if (primaryAlphaCount >= 12) {
            IconMaskData(gridW, gridH, primaryAlphaMask, secondaryAlphaMask)
        } else {
            // Entire icon is uniformly semi-transparent: treat full alpha mask as primary
            IconMaskData(gridW, gridH, alphaMask, BooleanArray(gridW * gridH))
        }

        val fullMask = maskData.fullMask
        var count = 0
        var minX = gridW
        var maxX = 0
        var minY = gridH
        var maxY = 0

        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                if (fullMask[gy * gridW + gx]) {
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

        // 1. Roundabout ring search (preserved intact, guarded against destination pin inner hole)
        if (!IconShapeClassifier.looksLikeDestination(fullMask, gridW, gridH)) {
            detectRoundaboutFromMask(fullMask, gridW, minX, maxX, minY, maxY, counterClockwise)?.let {
                return it
            }
        }

        // 2. Unified pure-Kotlin classifier: Destination (Pin+Road / Pin), U-Turn, Dual-Tone (Fork/Ramp/Keep/Merge),
        //    and 32x32 Template + Arrow Geometry (Straight, Slight L/R, Turn 90° L/R, Sharp 135° L/R)
        val classified = IconShapeClassifier.classify(maskData) ?: return null
        return ParsedIconManeuver(
            maneuverCode = classified.maneuverCode,
            confidence = if (classified.debugSummary.isNotBlank()) {
                "${classified.label}(${classified.debugSummary})"
            } else {
                classified.label
            },
            confidenceScore = classified.confidenceScore
        )
    }

    private fun extractContrastDualMasks(
        bitmap: Bitmap,
        step: Int,
        gridW: Int,
        gridH: Int
    ): IconMaskData? {
        val edgeLumas = mutableListOf<Int>()
        for (gx in 0 until gridW) {
            edgeLumas.add(pixelLuma(bitmap.getPixel(gx * step, 0)))
            edgeLumas.add(pixelLuma(bitmap.getPixel(gx * step, (gridH - 1) * step)))
        }
        val bgLuma = edgeLumas.sorted()[edgeLumas.size / 2]

        val diffs = IntArray(gridW * gridH)
        var maxDiff = 0
        for (gy in 0 until gridH) {
            val y = gy * step
            for (gx in 0 until gridW) {
                val pixel = bitmap.getPixel(gx * step, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha > 40) {
                    val d = abs(pixelLuma(pixel) - bgLuma)
                    diffs[gy * gridW + gx] = d
                    if (d > maxDiff) maxDiff = d
                }
            }
        }
        if (maxDiff < 55) return null

        val primaryThresh = maxOf(55, (maxDiff * 0.62).toInt())
        val secondaryThresh = maxOf(28, (maxDiff * 0.28).toInt())
        val primary = BooleanArray(gridW * gridH)
        val secondary = BooleanArray(gridW * gridH)
        var primCount = 0
        var secCount = 0

        for (i in diffs.indices) {
            val d = diffs[i]
            if (d >= primaryThresh) {
                primary[i] = true
                primCount++
            } else if (d >= secondaryThresh) {
                secondary[i] = true
                secCount++
            }
        }

        val totalFg = primCount + secCount
        val total = gridW * gridH
        if (totalFg !in 15..(total * 0.68).toInt()) return null
        return if (primCount >= 12) {
            IconMaskData(gridW, gridH, primary, secondary)
        } else {
            val combined = BooleanArray(gridW * gridH) { i -> primary[i] || secondary[i] }
            IconMaskData(gridW, gridH, combined, BooleanArray(gridW * gridH))
        }
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

                    if (coreTotal == 0 || ringHit < 20) continue
                    val coreDensity = coreHit.toDouble() / coreTotal
                    val coveredOctants = octantHits.count { it >= 2 }
                    if (coreDensity <= 0.20 && coveredOctants >= 7) {
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

        // Compute 7-spoke angular position (every 45°, excluding bottom entry stem at 160°..200°)
        // Matches Bajaj cluster LCD 7-arrow wheel:
        // RHT ('U'): 1=135°(4:30), 2=90°(3:00), 3=45°(1:30), 4=0°(12:00), 5=315°(10:30), 6=270°(9:00), 7=225°(7:30)
        // LHT ('N'): 1=225°(7:30), 2=270°(9:00), 3=315°(10:30), 4=0°(12:00), 5=45°(1:30), 6=90°(3:00), 7=135°(4:30)
        val exitMinR = bestR * 1.32
        val sectorWeight = DoubleArray(7) // index 0..6 => 135°, 90°, 45°, 0°, 315°, 270°, 225°
        var totalExitPixels = 0

        for (gy in minY..maxY) {
            for (gx in minX..maxX) {
                if (!fgMask[gy * gridW + gx]) continue
                val dx = gx - bestCx
                val dy = gy - bestCy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > exitMinR) {
                    val angleDeg = (Math.toDegrees(atan2(dx, -dy)) + 360.0) % 360.0
                    // Exclude the vertical entry stem directly below the ring (160..200 deg)
                    if (angleDeg in 160.0..200.0) continue
                    val weight = (dist - exitMinR) + 1.0
                    val sectorIdx = when {
                        angleDeg >= 112.5 && angleDeg < 160.0 -> 0 // 135° (bottom-right)
                        angleDeg >= 67.5 && angleDeg < 112.5 -> 1  // 90°  (right)
                        angleDeg >= 22.5 && angleDeg < 67.5 -> 2   // 45°  (top-right)
                        angleDeg >= 337.5 || angleDeg < 22.5 -> 3  // 0°   (straight)
                        angleDeg >= 292.5 && angleDeg < 337.5 -> 4 // 315° (top-left)
                        angleDeg >= 247.5 && angleDeg < 292.5 -> 5 // 270° (left)
                        else -> 6                                   // 225° (bottom-left)
                    }
                    sectorWeight[sectorIdx] += weight
                    totalExitPixels++
                }
            }
        }

        val inferredExit = if (totalExitPixels < 3) {
            4 // default: 12:00 straight ahead
        } else {
            var bestSector = 3
            var bestWeight = -1.0
            for (i in 0..6) {
                if (sectorWeight[i] > bestWeight) {
                    bestWeight = sectorWeight[i]
                    bestSector = i
                }
            }
            if (counterClockwise) {
                bestSector + 1 // 1..7 counter-clockwise from 135°
            } else {
                7 - bestSector // 1..7 clockwise from 225°
            }
        }

        val code = if (counterClockwise) {
            BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID
        } else {
            BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID
        }

        return ParsedIconManeuver(
            maneuverCode = code,
            roundaboutExit = inferredExit,
            isRoundabout = true,
            confidence = "bitmap:roundabout:pos$inferredExit",
            confidenceScore = 0.95f
        )
    }
}
