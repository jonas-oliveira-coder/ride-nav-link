package com.custom.bajajbridge

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
            val explicitClockwise = entryName.contains("cw") || entryName.contains("right") || entryName.contains("horario")
            val code = if (explicitClockwise || !counterClockwise) {
                BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID
            } else {
                BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID
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

        var sumX = 0L
        var sumY = 0L
        var count = 0
        var minX = w
        var maxX = 0
        var minY = h
        var maxY = 0

        val step = if (w > 128) 4 else if (w > 64) 2 else 1
        var totalSampled = 0
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                totalSampled++
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha > 40) {
                    sumX += x
                    sumY += y
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // Reject solid opaque square/circle backgrounds (e.g. adaptive icons without alpha cutout)
        if (count < 15 || (totalSampled > 0 && count > totalSampled * 0.78)) return null

        val cx = sumX.toDouble() / count
        val cy = sumY.toDouble() / count
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        val midX = (minX + maxX) / 2.0
        val midY = (minY + maxY) / 2.0

        val centerRadius = boxW * 0.16
        val ringInnerRadius = boxW * 0.20
        val ringOuterRadius = boxW * 0.44

        var centerCorePixels = 0
        var ringPixels = 0
        var exitTopPixels = 0
        var exitRightPixels = 0
        var exitBottomPixels = 0
        var exitLeftPixels = 0

        for (y in minY..maxY step step) {
            for (x in minX..maxX step step) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha > 40) {
                    val dx = x - midX
                    val dy = y - midY
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < centerRadius) {
                        centerCorePixels++
                    } else if (dist in ringInnerRadius..ringOuterRadius) {
                        ringPixels++
                    } else if (dist > ringOuterRadius) {
                        val angleDeg = (Math.toDegrees(atan2(dx, -dy)) + 360) % 360
                        when (angleDeg) {
                            in 315.0..360.0, in 0.0..45.0 -> exitTopPixels++
                            in 45.0..135.0 -> exitRightPixels++
                            in 135.0..225.0 -> exitBottomPixels++
                            in 225.0..315.0 -> exitLeftPixels++
                        }
                    }
                }
            }
        }

        val isRoundaboutAnnulus = ringPixels >= 25 && centerCorePixels <= (ringPixels * 0.35)
        if (isRoundaboutAnnulus) {
            val maxExitPixels = maxOf(exitTopPixels, exitRightPixels, exitBottomPixels, exitLeftPixels)
            val inferredExit = when {
                maxExitPixels == exitRightPixels && exitRightPixels >= 4 -> 1
                maxExitPixels == exitTopPixels && exitTopPixels >= 4 -> 2
                maxExitPixels == exitLeftPixels && exitLeftPixels >= 4 -> 3
                maxExitPixels == exitBottomPixels && exitBottomPixels >= 4 -> 4
                else -> 2
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
                confidence = "bitmap:roundabout:exit$inferredExit"
            )
        }

        val relCx = (cx - midX) / (boxW / 2.0)

        val topZone = minY + (boxH * 0.30)
        val bottomZone = maxY - (boxH * 0.30)
        val leftZone = minX + (boxW * 0.30)
        val rightZone = maxX - (boxW * 0.30)

        var topPixels = 0
        var rightPixels = 0
        var leftPixels = 0
        var bottomPixels = 0

        for (y in minY..maxY step step) {
            for (x in minX..maxX step step) {
                val pixel = bitmap.getPixel(x, y)
                if (((pixel ushr 24) and 0xFF) > 40) {
                    if (y <= topZone) topPixels++
                    if (y >= bottomZone) bottomPixels++
                    if (x <= leftZone) leftPixels++
                    if (x >= rightZone) rightPixels++
                }
            }
        }

        if (topPixels > bottomPixels * 1.3 && abs(relCx) < 0.28) {
            return ParsedIconManeuver(
                maneuverCode = BajajProtocol.ARROW_STRAIGHT_SOLID,
                confidence = "bitmap:straight"
            )
        }

        if (rightPixels > leftPixels * 1.4) {
            val code = when {
                topPixels > bottomPixels * 1.5 -> BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID
                bottomPixels > topPixels * 1.5 -> BajajProtocol.ARROW_SHARP_RIGHT_SOLID
                else -> BajajProtocol.ARROW_RIGHT_SOLID
            }
            return ParsedIconManeuver(maneuverCode = code, confidence = "bitmap:right")
        }

        if (leftPixels > rightPixels * 1.4) {
            val code = when {
                topPixels > bottomPixels * 1.5 -> BajajProtocol.ARROW_SLIGHT_LEFT_SOLID
                bottomPixels > topPixels * 1.5 -> BajajProtocol.ARROW_SHARP_LEFT_SOLID
                else -> BajajProtocol.ARROW_LEFT_SOLID
            }
            return ParsedIconManeuver(maneuverCode = code, confidence = "bitmap:left")
        }

        return null
    }
}
