package com.custom.bajajbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

enum class MaintenanceAlertLevel {
    OK,
    NEAR,
    OVERDUE
}

data class ServiceMilestone(
    val order: Int,
    val km: Int,
    val timeRuleLabel: String,
    val intervalMonthsFromPrevious: Int,
    val summary: String,
    val isCompleted: Boolean,
    val isCurrentTarget: Boolean
)

data class MaintenanceSnapshot(
    val riderName: String,
    val currentKm: Double,
    val gpsAddedKmSinceSync: Double,
    val previousMilestoneKm: Int,
    val nextMilestoneKm: Int,
    val nextMilestoneOrder: Int,
    val nextIntervalMonths: Int,
    val kmRemaining: Double,
    val progressFraction: Float,
    val hasBaseDate: Boolean,
    val baseDateMs: Long,
    val dueDateMs: Long,
    val daysRemaining: Int?,
    val alertLevel: MaintenanceAlertLevel,
    val alertHeadline: String,
    val alertDetail: String,
    val gpsEnabled: Boolean,
    val gpsOnlyWhenConnected: Boolean,
    val isBleConnected: Boolean,
    val isGpsActivelyTracking: Boolean,
    val gpsStatusText: String,
    val lastSpeedKmh: Int,
    val firstServiceMonths: Int,
    val calibrationFactor: Double = 1.0,
    val milestones: List<ServiceMilestone>
)

data class OdometerAdjustResult(
    val newWholeKm: Int,
    val deltaKm: Int,
    val calibrationFactor: Double,
    val calibrationUpdated: Boolean
)

@SuppressLint("MissingPermission")
class MaintenanceManager(
    private val context: Context,
    private val preferences: AppPreferences
) {

    companion object {
        const val CHANNEL_ID = "ridenav_maintenance_channel"
        const val NOTIFICATION_ID = 2001
        const val EXTRA_OPEN_TAB = "extra_open_tab"
        const val TAB_PROFILE_INDEX = 2

        const val NEAR_KM_THRESHOLD = 100.0
        const val NEAR_DAYS_THRESHOLD = 15

        private const val MIN_MOVING_SPEED_MPS = 2.8f // ~10 km/h (filtra caminhada e semáforo)
        private const val MAX_VALID_SPEED_MPS = 65.0  // ~234 km/h (filtra saltos irreais de GPS)
        private const val MIN_STEP_NO_SPEED_METERS = 15.0
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    private var isBleConnected = false
    private var isTrackingGps = false
    private var lastFixLocation: Location? = null
    private var lastGpsFixTimeMs = 0L
    private var lastAnyFixTimeMs = 0L
    private var lastSpeedKmh = 0

    var onSnapshotUpdated: ((MaintenanceSnapshot) -> Unit)? = null
    var onLocationTrackingModeChanged: ((Boolean) -> Unit)? = null

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
            if (now - lastGpsFixTimeMs > 5000L) {
                handleLocationFix(location, fromGps = false)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun isGpsActivelyTracking(): Boolean = isTrackingGps

    fun getLastLocation(): Location? = lastFixLocation

    fun setBleConnected(connected: Boolean) {
        if (isBleConnected == connected) return
        isBleConnected = connected
        refreshTrackingAndNotify()
    }

    fun refreshTrackingAndNotify() {
        val shouldTrack = preferences.isGpsOdometerEnabled() &&
            (!preferences.isGpsOdometerOnlyWhenConnected() || isBleConnected)

        if (shouldTrack) {
            ensureLocationUpdates()
        } else {
            stopLocationUpdates()
        }

        val snapshot = getSnapshot()
        evaluateAndPostNotification(snapshot)
        mainHandler.post { onSnapshotUpdated?.invoke(snapshot) }
    }

    fun setGpsOdometerSettings(enabled: Boolean, onlyWhenConnected: Boolean) {
        preferences.setGpsOdometerEnabled(enabled)
        preferences.setGpsOdometerOnlyWhenConnected(onlyWhenConnected)
        refreshTrackingAndNotify()
    }

    fun setRiderName(name: String) {
        preferences.setRiderName(name)
        emitSnapshot()
    }

    fun configureOdometerAndSchedule(
        odometerKm: Double,
        lastCompletedMilestoneKm: Int,
        baseDateMs: Long,
        firstServiceMonths: Int
    ) {
        val clampedKm = odometerKm.coerceIn(0.0, 999_999.0)
        preferences.setOdometerMeters(clampedKm * 1000.0)
        preferences.resetGpsAddedMetersSinceSync()
        preferences.setFirstServiceMonths(firstServiceMonths)
        if (baseDateMs > 0L) {
            preferences.setLastServiceDateMs(baseDateMs)
        } else {
            preferences.setLastServiceDateMs(0L)
        }

        val completed = LinkedHashSet<Int>()
        if (lastCompletedMilestoneKm >= 1000) {
            completed.add(1000)
            var km = 5000
            while (km <= lastCompletedMilestoneKm && km <= 500_000) {
                completed.add(km)
                km += 5000
            }
        }
        preferences.setCompletedServiceMilestones(completed)
        preferences.clearMaintenanceAlertHistory()
        refreshTrackingAndNotify()
    }

    fun adjustOdometerKmInteger(deltaKm: Int): OdometerAdjustResult {
        val currentKm = preferences.getOdometerMeters() / 1000.0
        val currentWhole: Int = when {
            deltaKm > 0 -> kotlin.math.floor(currentKm).toInt()
            deltaKm < 0 -> kotlin.math.ceil(currentKm).toInt()
            else -> kotlin.math.round(currentKm).toInt()
        }
        val rawNext: Int = currentWhole + deltaKm
        val nextWhole: Int = if (rawNext < 0) 0 else rawNext

        // Smart auto-calibration for GPS odometer error margin:
        // If at least 1.5 km of GPS has accumulated since last calibration, compute actual divergence
        val gpsAddedMeters = preferences.getGpsAddedMetersSinceSync()
        val gpsAddedKm = gpsAddedMeters / 1000.0
        var factorUpdated = false
        var currentFactor = preferences.getOdometerCalibrationFactor()

        if (gpsAddedKm >= 1.5 && deltaKm != 0) {
            val targetKm = gpsAddedKm + deltaKm.toDouble()
            if (targetKm > 0.3) {
                val observedRatio = targetKm / gpsAddedKm
                val clampedRatio = if (observedRatio < 0.80) 0.80 else if (observedRatio > 1.25) 1.25 else observedRatio
                val weight = if (gpsAddedKm >= 10.0) 0.35 else 0.20
                val newFactor = (currentFactor * (1.0 - weight)) + (clampedRatio * weight)
                val safeFactor = if (newFactor < 0.80) 0.80 else if (newFactor > 1.25) 1.25 else newFactor
                preferences.setOdometerCalibrationFactor(safeFactor)
                currentFactor = safeFactor
                factorUpdated = true
            }
        }

        preferences.setOdometerMeters(nextWhole * 1000.0)
        preferences.resetGpsAddedMetersSinceSync()
        preferences.clearMaintenanceAlertHistory()
        refreshTrackingAndNotify()

        return OdometerAdjustResult(
            newWholeKm = nextWhole,
            deltaKm = deltaKm,
            calibrationFactor = currentFactor,
            calibrationUpdated = factorUpdated
        )
    }

    fun resetOdometerCalibrationFactor() {
        preferences.setOdometerCalibrationFactor(1.0)
        refreshTrackingAndNotify()
    }

    fun registerServiceCompleted(milestoneKm: Int, serviceDateMs: Long = System.currentTimeMillis()) {
        val updated = LinkedHashSet(preferences.getCompletedServiceMilestones())
        updated.add(1000)
        if (milestoneKm >= 5000) {
            var km = 5000
            while (km <= milestoneKm && km <= 500_000) {
                updated.add(km)
                km += 5000
            }
        }
        preferences.setCompletedServiceMilestones(updated)
        preferences.setLastServiceDateMs(serviceDateMs)
        preferences.clearMaintenanceAlertHistory()
        refreshTrackingAndNotify()
    }

    fun toggleMilestoneCompleted(milestoneKm: Int) {
        val current = LinkedHashSet(preferences.getCompletedServiceMilestones())
        if (current.contains(milestoneKm)) {
            current.remove(milestoneKm)
        } else {
            current.add(milestoneKm)
        }
        preferences.setCompletedServiceMilestones(current)
        preferences.clearMaintenanceAlertHistory()
        refreshTrackingAndNotify()
    }

    fun emitSnapshot() {
        val snapshot = getSnapshot()
        evaluateAndPostNotification(snapshot)
        mainHandler.post { onSnapshotUpdated?.invoke(snapshot) }
    }

    fun getSnapshot(): MaintenanceSnapshot {
        val currentKm = (preferences.getOdometerMeters() / 1000.0).coerceAtLeast(0.0)
        val gpsAddedKm = (preferences.getGpsAddedMetersSinceSync() / 1000.0).coerceAtLeast(0.0)
        val firstMonths = preferences.getFirstServiceMonths()
        val completedSet = preferences.getCompletedServiceMilestones()
        val baseDateMs = preferences.getLastServiceDateMs()

        val allMilestones = buildMilestoneSchedule(
            currentKm = currentKm,
            completedSet = completedSet,
            firstServiceMonths = firstMonths
        )

        val nextTarget = allMilestones.firstOrNull { it.isCurrentTarget } ?: allMilestones.last()
        val nextKm = nextTarget.km
        val prevKm = when {
            nextKm <= 1000 -> 0
            nextKm == 5000 -> if (completedSet.contains(1000)) 1000 else 0
            else -> nextKm - 5000
        }
        val spanKm = max(1, nextKm - prevKm).toDouble()
        val progress = ((currentKm - prevKm) / spanKm).toFloat().coerceIn(0f, 1f)
        val kmRemaining = nextKm - currentKm

        val hasBaseDate = baseDateMs > 0L
        var dueDateMs = 0L
        var daysRemaining: Int? = null
        if (hasBaseDate) {
            val dueCal = Calendar.getInstance().apply {
                timeInMillis = baseDateMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MONTH, nextTarget.intervalMonthsFromPrevious)
            }
            dueDateMs = dueCal.timeInMillis

            val todayCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val diffMs = dueDateMs - todayCal.timeInMillis
            daysRemaining = (diffMs / 86_400_000L).toInt()
        }

        val isKmOverdue = kmRemaining <= 0.0
        val isTimeOverdue = daysRemaining != null && daysRemaining <= 0
        val isKmNear = !isKmOverdue && kmRemaining <= NEAR_KM_THRESHOLD
        val isTimeNear = !isTimeOverdue && daysRemaining != null && daysRemaining <= NEAR_DAYS_THRESHOLD

        val alertLevel = when {
            isKmOverdue || isTimeOverdue -> MaintenanceAlertLevel.OVERDUE
            isKmNear || isTimeNear -> MaintenanceAlertLevel.NEAR
            else -> MaintenanceAlertLevel.OK
        }

        val nextKmStr = formatIntKm(nextKm)
        val alertHeadline = when (alertLevel) {
            MaintenanceAlertLevel.OVERDUE -> "Revisão de $nextKmStr km vencida"
            MaintenanceAlertLevel.NEAR -> "Revisão de $nextKmStr km próxima"
            MaintenanceAlertLevel.OK -> "Revisões em dia"
        }

        val alertDetail = buildAlertDetail(
            alertLevel = alertLevel,
            nextKmStr = nextKmStr,
            intervalMonths = nextTarget.intervalMonthsFromPrevious,
            kmRemaining = kmRemaining,
            daysRemaining = daysRemaining,
            dueDateMs = dueDateMs
        )

        val gpsEnabled = preferences.isGpsOdometerEnabled()
        val onlyWhenConnected = preferences.isGpsOdometerOnlyWhenConnected()
        val gpsStatusText = when {
            !gpsEnabled -> "Pausado"
            onlyWhenConnected && !isBleConnected -> "Aguardando moto"
            !hasLocationPermission() -> "Sem GPS"
            isTrackingGps && lastSpeedKmh >= 10 -> "GPS ativo • $lastSpeedKmh km/h"
            isTrackingGps -> "GPS pronto"
            else -> "Inativo"
        }

        return MaintenanceSnapshot(
            riderName = preferences.getRiderName(),
            currentKm = currentKm,
            gpsAddedKmSinceSync = gpsAddedKm,
            previousMilestoneKm = prevKm,
            nextMilestoneKm = nextKm,
            nextMilestoneOrder = nextTarget.order,
            nextIntervalMonths = nextTarget.intervalMonthsFromPrevious,
            kmRemaining = kmRemaining,
            progressFraction = progress,
            hasBaseDate = hasBaseDate,
            baseDateMs = baseDateMs,
            dueDateMs = dueDateMs,
            daysRemaining = daysRemaining,
            alertLevel = alertLevel,
            alertHeadline = alertHeadline,
            alertDetail = alertDetail,
            gpsEnabled = gpsEnabled,
            gpsOnlyWhenConnected = onlyWhenConnected,
            isBleConnected = isBleConnected,
            isGpsActivelyTracking = isTrackingGps,
            gpsStatusText = gpsStatusText,
            lastSpeedKmh = lastSpeedKmh,
            firstServiceMonths = firstMonths,
            calibrationFactor = preferences.getOdometerCalibrationFactor(),
            milestones = allMilestones
        )
    }

    private fun buildAlertDetail(
        alertLevel: MaintenanceAlertLevel,
        nextKmStr: String,
        intervalMonths: Int,
        kmRemaining: Double,
        daysRemaining: Int?,
        dueDateMs: Long
    ): String {
        val dateStr = if (dueDateMs > 0L) formatDateShort(dueDateMs) else ""
        return when (alertLevel) {
            MaintenanceAlertLevel.OVERDUE -> {
                when {
                    kmRemaining <= 0.0 -> {
                        val passed = ceil(-kmRemaining).toInt().coerceAtLeast(0)
                        if (passed == 0) "Você atingiu o marco de $nextKmStr km."
                        else "Passou ${formatIntKm(passed)} km do marco de $nextKmStr km."
                    }
                    daysRemaining != null && daysRemaining < 0 -> {
                        val dStr = if (-daysRemaining == 1) "1 dia" else "${-daysRemaining} dias"
                        "Prazo de $intervalMonths meses venceu há $dStr ($dateStr)."
                    }
                    else ->
                        "Prazo de $intervalMonths meses vence hoje ($dateStr)."
                }
            }
            MaintenanceAlertLevel.NEAR -> {
                val remKm = ceil(kmRemaining).toInt().coerceAtLeast(1)
                val kmNear = kmRemaining <= NEAR_KM_THRESHOLD
                val timeNear = daysRemaining != null && daysRemaining <= NEAR_DAYS_THRESHOLD
                val dStr = if (daysRemaining == 1) "1 dia" else "$daysRemaining dias"
                when {
                    kmNear && timeNear ->
                        "Faltam $remKm km ou $dStr ($dateStr) para a revisão de $nextKmStr km."
                    kmNear ->
                        "Faltam $remKm km para a revisão de $nextKmStr km."
                    else ->
                        "Faltam $dStr para o prazo de $intervalMonths meses ($dateStr)."
                }
            }
            MaintenanceAlertLevel.OK -> {
                val remKm = ceil(kmRemaining).toInt().coerceAtLeast(0)
                if (daysRemaining != null) {
                    "Faltam ${formatIntKm(remKm)} km ou $daysRemaining dias ($dateStr)."
                } else {
                    "Faltam ${formatIntKm(remKm)} km para a próxima revisão."
                }
            }
        }
    }

    private fun buildMilestoneSchedule(
        currentKm: Double,
        completedSet: Set<Int>,
        firstServiceMonths: Int
    ): List<ServiceMilestone> {
        val maxCompleted = completedSet.maxOrNull() ?: 0
        val highestTarget = maxOf(30_000, ((currentKm.toInt() / 5000) + 2) * 5000, maxCompleted + 5000)
            .coerceAtMost(200_000)

        val kms = mutableListOf(1000)
        var k = 5000
        while (k <= highestTarget) {
            kms.add(k)
            k += 5000
        }

        var foundCurrentTarget = false
        val list = mutableListOf<ServiceMilestone>()
        kms.forEachIndexed { idx, milestoneKm ->
            val order = idx + 1
            val isDone = completedSet.contains(milestoneKm)
            val isTarget = !isDone && !foundCurrentTarget
            if (isTarget) {
                foundCurrentTarget = true
            }
            val cumulativeMonths = if (milestoneKm == 1000) firstServiceMonths else (milestoneKm / 5000) * 6
            val intervalFromPrev = if (milestoneKm == 1000) firstServiceMonths else 6
            val timeRule = if (milestoneKm == 1000) {
                if (firstServiceMonths == 1) "1 mês (mão de obra gratuita)" else "3 meses (mão de obra gratuita)"
            } else {
                "$cumulativeMonths meses (+6m)"
            }
            list.add(
                ServiceMilestone(
                    order = order,
                    km = milestoneKm,
                    timeRuleLabel = timeRule,
                    intervalMonthsFromPrevious = intervalFromPrev,
                    summary = checklistSummaryFor(milestoneKm),
                    isCompleted = isDone,
                    isCurrentTarget = isTarget
                )
            )
        }
        return list
    }

    private fun checklistSummaryFor(km: Int): String {
        if (km == 1000) {
            return "Troca de óleo e filtro, limpeza da tela, corrente e inspeção geral"
        }
        val cycleKm = ((km - 5000) % 30_000) + 5000
        return when (cycleKm) {
            5000 -> "Óleo e filtro de óleo, corrente, freios e inspeções gerais"
            10000 -> "Óleo e filtro de óleo, checagem ampliada de fixações e velas"
            15000 -> "Óleo e filtro de óleo, filtro de ar (160/N150) e inspeções"
            20000 -> "Óleo e filtro, filtro de ar (200/250/400) e folga das válvulas"
            25000 -> "Óleo e filtro de óleo e revisão intermediária"
            30000 -> "Óleo e filtro, velas, fluido de freio DOT 4 e arrefecimento"
            else -> "Óleo e filtro de óleo e inspeções periódicas"
        }
    }

    private fun handleLocationFix(location: Location, fromGps: Boolean) {
        if (!preferences.isGpsOdometerEnabled()) return
        if (preferences.isGpsOdometerOnlyWhenConnected() && !isBleConnected) return

        val now = System.currentTimeMillis()
        if (location.hasAccuracy() && location.accuracy > 25f && (now - lastAnyFixTimeMs) < 12_000L) {
            return
        }

        val prevAnyFixTime = lastAnyFixTimeMs
        if (fromGps) {
            lastGpsFixTimeMs = now
        }
        lastAnyFixTimeMs = now

        val prev = lastFixLocation
        if (prev == null) {
            lastFixLocation = location
            return
        }

        val dtMs = (location.time - prev.time).let { if (it in 100L..15_000L) it else (now - prevAnyFixTime).coerceIn(500L, 5000L) }
        val rawDtSec = dtMs / 1000.0
        val dtSec = if (rawDtSec < 0.2) 0.2 else rawDtSec
        val rawStep = prev.distanceTo(location).toDouble()
        val speedCalc = rawStep / dtSec

        // Discard unrealistic GPS jumps (> 234 km/h)
        if (speedCalc > MAX_VALID_SPEED_MPS) {
            lastFixLocation = location
            return
        }

        val hasValidSpeed = location.hasSpeed() && location.speed >= 1.0f
        val effectiveSpeed: Double = if (hasValidSpeed) location.speed.toDouble() else speedCalc
        val accMeters = (location.accuracy.takeIf { it > 0f } ?: 10f).toDouble()

        val minStep = if (hasValidSpeed) {
            val s = accMeters * 0.3
            if (s < 3.0) 3.0 else s
        } else {
            val s = accMeters * 0.5
            if (s < MIN_STEP_NO_SPEED_METERS) MIN_STEP_NO_SPEED_METERS else s
        }

        // Do not accumulate mileage when stopped at lights or walking (< ~10 km/h)
        if (effectiveSpeed < MIN_MOVING_SPEED_MPS || rawStep < minStep) {
            lastSpeedKmh = if (effectiveSpeed >= 1.5) ((effectiveSpeed * 3.6) + 0.5).toInt() else 0
            return
        }

        // Bounded speed projection: use Doppler speed to filter GPS drift and prevent accumulated errors
        val calculatedStep = if (hasValidSpeed && dtSec >= 0.4 && dtSec <= 6.0) {
            val speedDist: Double = effectiveSpeed * dtSec
            val lower = speedDist * 0.75
            val upper = speedDist * 1.25
            if (rawStep < lower) lower else if (rawStep > upper) upper else rawStep
        } else {
            rawStep
        }

        val factor = preferences.getOdometerCalibrationFactor()
        val finalStep = calculatedStep * factor

        lastFixLocation = location
        val rawKmh = ((effectiveSpeed * 3.6) + 0.5).toInt()
        lastSpeedKmh = if (rawKmh < 0) 0 else if (rawKmh > 234) 234 else rawKmh
        preferences.addOdometerMeters(finalStep)

        val snapshot = getSnapshot()
        evaluateAndPostNotification(snapshot)
        mainHandler.post { onSnapshotUpdated?.invoke(snapshot) }
    }

    private fun evaluateAndPostNotification(snapshot: MaintenanceSnapshot) {
        if (snapshot.alertLevel == MaintenanceAlertLevel.OK) return

        val tier = when {
            snapshot.alertLevel == MaintenanceAlertLevel.OVERDUE -> "OVERDUE"
            snapshot.kmRemaining <= NEAR_KM_THRESHOLD -> "NEAR_KM"
            else -> "NEAR_TIME"
        }

        val lastKm = preferences.getLastNotifiedMilestoneKm()
        val lastTier = preferences.getLastNotifiedTier()
        if (lastKm == snapshot.nextMilestoneKm && lastTier == tier) {
            return
        }
        // If milestone overdue alert already fired, do not downgrade back to NEAR
        if (lastKm == snapshot.nextMilestoneKm && lastTier == "OVERDUE") {
            return
        }

        preferences.setLastNotifiedAlert(snapshot.nextMilestoneKm, tier)
        postMaintenanceNotification(snapshot.alertHeadline, snapshot.alertDetail)
    }

    private fun postMaintenanceNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createNotificationChannel(manager)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, TAB_PROFILE_INDEX)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_brand_mark)
            .setColor(Color.parseColor("#0B57D0"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Revisões e Manutenção",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Avisa quando falta pouco para a próxima revisão por km ou prazo"
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun hasLocationPermission(): Boolean {
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
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 10f, gpsListener, Looper.getMainLooper())
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { seed ->
                    if (System.currentTimeMillis() - seed.time < 30_000L) {
                        lastFixLocation = seed
                        lastGpsFixTimeMs = System.currentTimeMillis()
                        lastAnyFixTimeMs = lastGpsFixTimeMs
                    }
                }
                registeredAny = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providers.contains(LocationManager.FUSED_PROVIDER)) {
                    runCatching {
                        lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 3000L, 10f, fallbackListener, Looper.getMainLooper())
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providers.contains(LocationManager.FUSED_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 3000L, 10f, fallbackListener, Looper.getMainLooper())
                registeredAny = true
            } else if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 15f, fallbackListener, Looper.getMainLooper())
                registeredAny = true
            }
            if (registeredAny) {
                isTrackingGps = true
                onLocationTrackingModeChanged?.invoke(true)
            }
        } catch (_: Exception) {
            isTrackingGps = false
        }
    }

    private fun stopLocationUpdates() {
        if (!isTrackingGps) {
            lastFixLocation = null
            lastSpeedKmh = 0
            return
        }
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
        lastFixLocation = null
        lastSpeedKmh = 0
        onLocationTrackingModeChanged?.invoke(false)
    }

    fun formatIntKm(km: Int): String {
        return String.format(Locale("pt", "BR"), "%,d", km)
    }

    fun formatDecimalKm(km: Double): String {
        return String.format(Locale("pt", "BR"), "%,.1f", km)
    }

    fun formatDateShort(ms: Long): String {
        if (ms <= 0L) return "--/--/----"
        return SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(ms))
    }
}
