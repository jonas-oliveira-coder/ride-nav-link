package com.custom.ridenavlink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class HybridNavOutput(
    val isActive: Boolean,
    val distanceMeters: Int,
    val totalDistanceMeters: Int,
    val maneuverCode: Byte,
    val solidManeuverCode: Byte,
    val streetText: String,
    val roundaboutExit: Int,
    val etaSeconds: Int,
    val etaHours: Int,
    val etaMinutes: Int,
    val isAm: Boolean?,
    val sourceLabel: String,
    val gpsActive: Boolean,
    val gpsAccuracyMeters: Int?,
    val isArrived: Boolean
)

@SuppressLint("MissingPermission")
class MapsHybridNavigator(
    private val context: Context,
    private val mainHandler: Handler,
    private val onOutput: (HybridNavOutput) -> Unit,
    private val onAutoStopped: () -> Unit,
    private val onGpsTrackingChanged: (Boolean) -> Unit
) {

    companion object {
        private const val ARRIVAL_HOLD_DURATION_MS = 35_000L
        private const val ARRIVAL_POST_CLEAR_MIN_MS = 22_000L
    }

    private var activeProvider: NavProvider = NavProvider.GOOGLE_MAPS
    private var isNavigating = false
    private var isArrivedState = false
    private var isRerouting = false
    private var arrivalEnteredMs = 0L

    private var anchorTurnMeters = 0
    private var anchorTotalMeters = 0
    private var anchorSolidManeuver = BajajProtocol.ARROW_STRAIGHT_SOLID
    private var anchorStreetText = "Google Maps"
    private var anchorStreetNorm = ""
    private var anchorRoundaboutExit = 0
    private var anchorEtaSeconds = -1
    private var anchorEtaHours = -1
    private var anchorEtaMinutes = -1
    private var anchorIsAm: Boolean? = null
    private var anchorTimeMs = 0L

    private var traveledSinceAnchorMeters = 0.0
    private var lastEmittedTurnMeters = -1
    private var lastEmittedManeuver: Byte = 0

    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }
    private var isTrackingGps = false
    private var lastFixLocation: Location? = null
    private var lastGpsFixTimeMs = 0L
    private var lastAnyFixTimeMs = 0L
    private var lastAccuracyMeters: Int? = null

    private val arrivalHoldRunnable = Runnable {
        if (isNavigating && isArrivedState) {
            stopAndNotify()
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocationFix(location, fromGps = true)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val fallbackListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = System.currentTimeMillis()
            // Only use fallback provider (Fused/Network) if dedicated GPS has no fix for > 3.5s
            if (now - lastGpsFixTimeMs > 3500L) {
                handleLocationFix(location, fromGps = false)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun isGpsActivelyRefining(): Boolean {
        if (!isNavigating || isArrivedState || !isTrackingGps) return false
        return (System.currentTimeMillis() - lastAnyFixTimeMs) <= 12_000L
    }

    fun getLastAccuracyMeters(): Int? = if (isGpsActivelyRefining()) lastAccuracyMeters else null

    private fun enterArrivalState(provider: NavProvider, displayText: String, parsed: ParsedMapsNavigation? = null) {
        val now = System.currentTimeMillis()
        val alreadyArrived = isNavigating && isArrivedState && activeProvider == provider
        activeProvider = provider
        isNavigating = true
        isArrivedState = true
        isRerouting = false
        if (!alreadyArrived) {
            arrivalEnteredMs = now
        }
        anchorTurnMeters = 0
        anchorTotalMeters = 0
        anchorSolidManeuver = BajajProtocol.ARROW_DESTINATION_SOLID
        anchorStreetText = displayText.ifBlank { "Você chegou ao destino" }
        anchorStreetNorm = normalizeStreet(anchorStreetText)
        anchorRoundaboutExit = 0
        anchorEtaSeconds = 0
        if (parsed != null) {
            anchorEtaHours = parsed.etaHours
            anchorEtaMinutes = parsed.etaMinutes
            anchorIsAm = parsed.isAm
        }
        anchorTimeMs = now
        traveledSinceAnchorMeters = 0.0
        stopLocationUpdates()
        if (!alreadyArrived) {
            mainHandler.removeCallbacks(arrivalHoldRunnable)
            mainHandler.postDelayed(arrivalHoldRunnable, ARRIVAL_HOLD_DURATION_MS)
        }
        emitCurrentOutput()
    }

    fun onMapsAnchorUpdate(parsed: ParsedMapsNavigation, provider: NavProvider = NavProvider.GOOGLE_MAPS) {
        val now = System.currentTimeMillis()

        if (parsed.isArrival) {
            enterArrivalState(provider, parsed.displayStreetText, parsed)
            return
        }

        // Prevent exiting arrival state due to residual end-of-route notifications
        if (isNavigating && isArrivedState &&
            provider == activeProvider &&
            parsed.solidManeuverCode == BajajProtocol.ARROW_DESTINATION_SOLID &&
            parsed.distanceMeters <= 35 &&
            (now - arrivalEnteredMs) < 12_000L
        ) {
            return
        }

        mainHandler.removeCallbacks(arrivalHoldRunnable)

        val wasNavigating = isNavigating
        val sameProvider = (provider == activeProvider)
        if (!sameProvider) {
            lastFixLocation = null
            traveledSinceAnchorMeters = 0.0
        }
        activeProvider = provider

        val newStreetNorm = normalizeStreet(parsed.displayStreetText)
        val sameManeuver = wasNavigating &&
            sameProvider &&
            !isArrivedState &&
            parsed.solidManeuverCode == anchorSolidManeuver &&
            (newStreetNorm == anchorStreetNorm || newStreetNorm.isEmpty() || anchorStreetNorm.isEmpty())

        val currentInterpolatedTurn = currentInterpolatedTurnMeters()
        val currentInterpolatedTotal = currentInterpolatedTotalMeters(currentInterpolatedTurn)

        isNavigating = true
        isArrivedState = false
        isRerouting = parsed.isRerouting
        anchorSolidManeuver = parsed.solidManeuverCode
        if (parsed.displayStreetText.isNotBlank()) {
            anchorStreetText = parsed.displayStreetText
            anchorStreetNorm = newStreetNorm
        }
        anchorRoundaboutExit = parsed.roundaboutExit
        anchorEtaSeconds = parsed.etaSeconds
        anchorEtaHours = parsed.etaHours
        anchorEtaMinutes = parsed.etaMinutes
        anchorIsAm = parsed.isAm
        anchorTimeMs = now

        if (!wasNavigating || !sameManeuver) {
            anchorTurnMeters = parsed.distanceMeters
            anchorTotalMeters = max(parsed.distanceMeters, parsed.totalDistanceMeters)
            traveledSinceAnchorMeters = 0.0
        } else if (parsed.isRerouting) {
            anchorTurnMeters = parsed.distanceMeters
            anchorTotalMeters = max(parsed.distanceMeters, parsed.totalDistanceMeters)
            traveledSinceAnchorMeters = 0.0
        } else if (!parsed.isTurnDistanceExact) {
            // ETD/ETA update without new turn distance: preserve local odometer count
            anchorTurnMeters = currentInterpolatedTurn
            anchorTotalMeters = max(currentInterpolatedTurn, parsed.totalDistanceMeters)
            traveledSinceAnchorMeters = 0.0
        } else if (parsed.distanceMeters == anchorTurnMeters) {
            // Navigation re-posted same anchor (e.g. ETA shift): preserve GPS progress
            anchorTotalMeters = minOf(currentInterpolatedTotal, max(currentInterpolatedTurn, parsed.totalDistanceMeters))
        } else {
            val diff = parsed.distanceMeters - currentInterpolatedTurn
            if (diff in 1..60) {
                // Navigation app sent anchor slightly behind GPS odometer: prevent jumping backwards
                anchorTurnMeters = parsed.distanceMeters
                anchorTotalMeters = max(currentInterpolatedTurn, parsed.totalDistanceMeters)
                traveledSinceAnchorMeters = diff.toDouble()
            } else {
                // New official navigation anchor or route recalculation
                anchorTurnMeters = parsed.distanceMeters
                anchorTotalMeters = max(parsed.distanceMeters, parsed.totalDistanceMeters)
                traveledSinceAnchorMeters = 0.0
            }
        }

        ensureLocationUpdates()
        emitCurrentOutput()
    }

    fun onMapsNotificationCleared() {
        if (!isNavigating) return

        val now = System.currentTimeMillis()
        if (isArrivedState) {
            val elapsed = (now - arrivalEnteredMs).coerceAtLeast(0L)
            val remaining = max(ARRIVAL_POST_CLEAR_MIN_MS, ARRIVAL_HOLD_DURATION_MS - elapsed)
                .coerceAtMost(ARRIVAL_HOLD_DURATION_MS)
            mainHandler.removeCallbacks(arrivalHoldRunnable)
            mainHandler.postDelayed(arrivalHoldRunnable, remaining)
            return
        }

        val currentTurn = currentInterpolatedTurnMeters()
        val currentTotal = currentInterpolatedTotalMeters(currentTurn)
        val nearDestinationEnd = anchorSolidManeuver == BajajProtocol.ARROW_DESTINATION_SOLID ||
            currentTotal <= 350 ||
            currentTurn <= 180

        if (nearDestinationEnd) {
            enterArrivalState(activeProvider, "Você chegou ao destino", null)
        } else {
            stopAndNotify()
        }
    }

    fun reset() {
        mainHandler.removeCallbacks(arrivalHoldRunnable)
        isNavigating = false
        isArrivedState = false
        isRerouting = false
        arrivalEnteredMs = 0L
        traveledSinceAnchorMeters = 0.0
        lastEmittedTurnMeters = -1
        lastEmittedManeuver = 0
        lastFixLocation = null
        lastGpsFixTimeMs = 0L
        lastAnyFixTimeMs = 0L
        lastAccuracyMeters = null
        stopLocationUpdates()
    }

    private fun stopAndNotify() {
        reset()
        onAutoStopped()
    }

    private fun handleLocationFix(location: Location, fromGps: Boolean) {
        if (!isNavigating || isArrivedState) return

        val now = System.currentTimeMillis()
        // Filter poor accuracy fixes (> 25m) if recent signal is available, eliminating 15m-30m jitter
        if (location.hasAccuracy() && location.accuracy > 25f && (now - lastAnyFixTimeMs) < 8_000L) {
            return
        }

        if (fromGps) {
            lastGpsFixTimeMs = now
        }
        lastAnyFixTimeMs = now
        lastAccuracyMeters = if (location.hasAccuracy()) location.accuracy.roundToInt() else null

        if (isRerouting) {
            lastFixLocation = location
            return
        }

        val prev = lastFixLocation
        if (prev == null) {
            lastFixLocation = location
            emitCurrentOutput()
            return
        }

        val dtMs = (location.time - prev.time).let { if (it in 50L..10_000L) it else (now - lastAnyFixTimeMs).coerceIn(250L, 4000L) }
        val dtSec = dtMs / 1000.0
        val rawStep = prev.distanceTo(location).toDouble()

        // Discard unrealistic GPS jumps (> 234 km/h)
        if (dtSec > 0.0 && (rawStep / dtSec) > 65.0) {
            lastFixLocation = location
            return
        }

        val hasValidSpeed = location.hasSpeed() && location.speed >= 0.8f
        val effectiveSpeed: Double = if (hasValidSpeed) location.speed.toDouble() else (rawStep / dtSec)
        val accMeters = (location.accuracy.takeIf { it > 0f } ?: 10f).toDouble()
        val scaledAcc = accMeters * 0.2
        val cappedAcc = if (scaledAcc > 6.0) 6.0 else scaledAcc
        val minStep = if (cappedAcc < 2.0) 2.0 else cappedAcc

        // If motorcycle is stopped or idling (< 0.8 m/s), do not consume meters on noise
        if (effectiveSpeed < 0.8 || rawStep < minStep) {
            return
        }

        // Bounded speed projection: use direct physical velocity to mitigate 15m-30m lateral GPS drift
        val stepMeters = if (hasValidSpeed && dtSec >= 0.25 && dtSec <= 4.0) {
            val speedDist: Double = effectiveSpeed * dtSec
            val lower = speedDist * 0.7
            val upper = speedDist * 1.3
            if (rawStep < lower) lower else if (rawStep > upper) upper else rawStep
        } else {
            rawStep
        }

        lastFixLocation = location
        traveledSinceAnchorMeters += stepMeters

        val newTurn = currentInterpolatedTurnMeters()
        val newTotal = currentInterpolatedTotalMeters(newTurn)
        if ((anchorSolidManeuver == BajajProtocol.ARROW_DESTINATION_SOLID && anchorTurnMeters > 0 && newTurn <= 25) ||
            (anchorTotalMeters > 0 && anchorTotalMeters <= 120 && newTotal <= 15 && newTurn <= 15)
        ) {
            enterArrivalState(activeProvider, "Você chegou ao destino", null)
            return
        }

        val newCode = BajajProtocol.maneuverForDistance(anchorSolidManeuver, newTurn)
        if (newTurn != lastEmittedTurnMeters || newCode != lastEmittedManeuver) {
            emitCurrentOutput()
        }
    }

    private fun currentInterpolatedTurnMeters(): Int {
        if (!isNavigating || isArrivedState) return 0
        return (anchorTurnMeters - traveledSinceAnchorMeters).roundToInt().coerceAtLeast(0)
    }

    private fun currentInterpolatedTotalMeters(turnMeters: Int): Int {
        if (!isNavigating || isArrivedState) return 0
        return (anchorTotalMeters - traveledSinceAnchorMeters).roundToInt().coerceAtLeast(turnMeters)
    }

    private fun emitCurrentOutput() {
        if (!isNavigating) return

        val turnMeters = if (isArrivedState) 0 else currentInterpolatedTurnMeters()
        val totalMeters = if (isArrivedState) 0 else currentInterpolatedTotalMeters(turnMeters)
        val maneuverCode = if (isArrivedState) {
            BajajProtocol.ARROW_DESTINATION_BLINK
        } else {
            BajajProtocol.maneuverForDistance(anchorSolidManeuver, turnMeters)
        }

        val elapsedSec = if (anchorTimeMs > 0L) {
            ((System.currentTimeMillis() - anchorTimeMs) / 1000L).toInt().coerceAtLeast(0)
        } else 0
        val adjustedEtaSec = if (isArrivedState) {
            0
        } else if (anchorEtaSeconds >= 0) {
            (anchorEtaSeconds - elapsedSec).coerceAtLeast(0)
        } else {
            -1
        }

        val gpsRefining = isGpsActivelyRefining()
        lastEmittedTurnMeters = turnMeters
        lastEmittedManeuver = maneuverCode

        onOutput(
            HybridNavOutput(
                isActive = true,
                distanceMeters = turnMeters,
                totalDistanceMeters = totalMeters,
                maneuverCode = maneuverCode,
                solidManeuverCode = anchorSolidManeuver,
                streetText = anchorStreetText,
                roundaboutExit = anchorRoundaboutExit,
                etaSeconds = adjustedEtaSec,
                etaHours = anchorEtaHours,
                etaMinutes = anchorEtaMinutes,
                isAm = anchorIsAm,
                sourceLabel = if (gpsRefining) activeProvider.hybridDisplayName else activeProvider.displayName,
                gpsActive = gpsRefining,
                gpsAccuracyMeters = getLastAccuracyMeters(),
                isArrived = isArrivedState
            )
        )
    }

    private fun hasLocationPermission(): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun ensureLocationUpdates() {
        if (isTrackingGps || !hasLocationPermission()) return
        val lm = locationManager ?: return

        try {
            var registeredAny = false
            val providers = lm.allProviders
            val gpsAvailable = providers.contains(LocationManager.GPS_PROVIDER) &&
                runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

            if (gpsAvailable) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, gpsListener, mainHandler.looper)
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { seed ->
                    if (System.currentTimeMillis() - seed.time < 20_000L) {
                        lastFixLocation = seed
                        lastGpsFixTimeMs = System.currentTimeMillis()
                        lastAnyFixTimeMs = lastGpsFixTimeMs
                    }
                }
                registeredAny = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providers.contains(LocationManager.FUSED_PROVIDER)) {
                    runCatching {
                        lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 1200L, 2f, fallbackListener, mainHandler.looper)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providers.contains(LocationManager.FUSED_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 1200L, 2f, fallbackListener, mainHandler.looper)
                registeredAny = true
            } else if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, fallbackListener, mainHandler.looper)
                registeredAny = true
            }
            if (registeredAny) {
                isTrackingGps = true
                onGpsTrackingChanged(true)
            }
        } catch (_: Exception) {
            isTrackingGps = false
        }
    }

    private fun stopLocationUpdates() {
        if (!isTrackingGps) return
        val lm = locationManager
        try {
            lm?.removeUpdates(gpsListener)
        } catch (_: Exception) {
        }
        try {
            lm?.removeUpdates(fallbackListener)
        } catch (_: Exception) {
        }
        isTrackingGps = false
        onGpsTrackingChanged(false)
    }

    private fun normalizeStreet(raw: String): String {
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.US)
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
