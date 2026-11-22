package com.custom.ridenavlink

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

enum class PanelAlertMode(
    val id: String,
    val label: String,
    val shortLabel: String,
    val description: String
) {
    // Internal protocol: CHAR_ALERTS type 2
    BLINK_WAPP(
        id = "blink_wapp",
        label = "Piscando • Mensagens e apps",
        shortLabel = "Piscando (Mensagens)",
        description = "O ícone de mensagem pisca no painel ao receber uma nova notificação."
    ),
    // Internal protocol: CHAR_ALERTS type 1
    BLINK_SMS(
        id = "blink_sms",
        label = "Piscando • SMS",
        shortLabel = "Piscando (SMS)",
        description = "Faz o indicador de SMS piscar no painel ao receber uma mensagem."
    ),
    // Internal protocol: CHAR_CALLS (Solid alert / Call on dash)
    SOLID_ALERT(
        id = "solid_alert",
        label = "Aceso fixo • Aviso no painel",
        shortLabel = "Aceso Fixo (Painel)",
        description = "Mantém o aviso aceso no painel com o nome do aplicativo e mensagem."
    );

    companion object {
        fun fromId(id: String?): PanelAlertMode {
            return values().firstOrNull { it.id == id } ?: BLINK_WAPP
        }
    }
}

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "bajaj_open_prefs"
        private const val KEY_PINNED_ADDRESS = "pinned_device_address"
        private const val KEY_PINNED_NAME = "pinned_device_name"
        private const val KEY_CUSTOM_NAME_PREFIX = "custom_device_name_"
        private const val KEY_SELECTED_PACKAGES = "selected_notification_packages"
        private const val KEY_PACKAGES_INITIALIZED = "notification_packages_initialized"
        private const val KEY_PANEL_ALERT_MODE = "panel_alert_mode"
        private const val KEY_ASKED_BATTERY_OPT = "asked_battery_opt"
        private const val KEY_AUTO_START_BG = "auto_start_background"
        private const val KEY_CLUSTER_CONTROLS_ENABLED = "cluster_controls_enabled"
        private const val KEY_NATIVE_SMS_ENABLED = "native_sms_enabled"
        private const val KEY_AUTO_SMS_ON_REJECT = "auto_sms_on_reject"
        private const val KEY_QUICK_REPLY_SMS = "quick_reply_sms_text"
        private const val KEY_HOME_NAV_QUERY = "home_navigation_query"
        private const val KEY_PREFERRED_MEDIA_PKG = "preferred_media_package"
        private const val KEY_RIDER_NAME = "rider_profile_name"
        private const val KEY_ODOMETER_METERS = "odometer_meters_str"
        private const val KEY_GPS_ADDED_METERS = "odometer_gps_added_meters_str"
        private const val KEY_LAST_SERVICE_DATE_MS = "last_service_date_ms"
        private const val KEY_FIRST_SERVICE_MONTHS = "first_service_months"
        private const val KEY_COMPLETED_MILESTONES = "completed_service_milestones"
        private const val KEY_GPS_ODOMETER_ENABLED = "gps_odometer_enabled"
        private const val KEY_GPS_ODOMETER_ONLY_CONNECTED = "gps_odometer_only_connected"
        private const val KEY_ODOMETER_CALIBRATION_FACTOR = "odometer_calibration_factor"
        private const val KEY_ROUNDABOUT_COUNTER_CLOCKWISE = "roundabout_counter_clockwise"
        private const val KEY_LAST_NOTIFIED_MILESTONE_KM = "last_notified_milestone_km"
        private const val KEY_LAST_NOTIFIED_TIER = "last_notified_tier"

        const val DEFAULT_QUICK_REPLY_SMS = "Estou pilotando, ligo assim que possível."

        val DEFAULT_NOTIFICATION_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "org.telegram.messenger"
        )
    }

    fun getPinnedAddress(): String? = prefs.getString(KEY_PINNED_ADDRESS, null)?.takeIf { it.isNotBlank() }

    fun getPinnedName(): String? = prefs.getString(KEY_PINNED_NAME, null)?.takeIf { it.isNotBlank() }

    fun getCustomDeviceName(address: String?): String? {
        if (address.isNullOrBlank()) return null
        val key = KEY_CUSTOM_NAME_PREFIX + address.uppercase()
        return prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setCustomDeviceName(address: String, customName: String) {
        if (address.isBlank()) return
        val key = KEY_CUSTOM_NAME_PREFIX + address.uppercase()
        val cleaned = customName.trim()
        prefs.edit().apply {
            if (cleaned.isEmpty()) remove(key)
            else putString(key, cleaned)
        }.apply()
    }

    fun getDisplayName(address: String?, fallbackBleName: String? = null): String {
        val custom = getCustomDeviceName(address)
        if (!custom.isNullOrBlank()) return custom
        val raw = fallbackBleName?.trim()?.takeIf { it.isNotEmpty() }
        if (raw != null) return raw
        if (!address.isNullOrBlank() && isPinned(address)) {
            getPinnedName()?.let { return it }
        }
        return "Moto Bajaj"
    }

    fun pinDevice(address: String, name: String) {
        prefs.edit()
            .putString(KEY_PINNED_ADDRESS, address)
            .putString(KEY_PINNED_NAME, name.ifBlank { "Moto Bajaj" })
            .apply()
    }

    fun unpinDevice() {
        prefs.edit()
            .remove(KEY_PINNED_ADDRESS)
            .remove(KEY_PINNED_NAME)
            .apply()
    }

    fun isPinned(address: String?): Boolean {
        val pinned = getPinnedAddress() ?: return false
        return !address.isNullOrBlank() && pinned.equals(address, ignoreCase = true)
    }

    fun isAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START_BG, true)

    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START_BG, enabled).apply()
    }

    fun getSelectedNotificationPackages(): Set<String> {
        if (!prefs.getBoolean(KEY_PACKAGES_INITIALIZED, false)) {
            return DEFAULT_NOTIFICATION_PACKAGES
        }
        return prefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun setSelectedNotificationPackages(packages: Set<String>) {
        prefs.edit()
            .putBoolean(KEY_PACKAGES_INITIALIZED, true)
            .putStringSet(KEY_SELECTED_PACKAGES, packages.toSet())
            .apply()
    }

    fun isPackageSelectedForAlerts(packageName: String): Boolean {
        return getSelectedNotificationPackages().contains(packageName)
    }

    fun getPanelAlertMode(): PanelAlertMode {
        return PanelAlertMode.fromId(prefs.getString(KEY_PANEL_ALERT_MODE, PanelAlertMode.BLINK_WAPP.id))
    }

    fun setPanelAlertMode(mode: PanelAlertMode) {
        prefs.edit().putString(KEY_PANEL_ALERT_MODE, mode.id).apply()
    }

    fun hasAskedBatteryOptimization(): Boolean = prefs.getBoolean(KEY_ASKED_BATTERY_OPT, false)

    fun markAskedBatteryOptimization() {
        prefs.edit().putBoolean(KEY_ASKED_BATTERY_OPT, true).apply()
    }

    fun isClusterControlsEnabled(): Boolean = prefs.getBoolean(KEY_CLUSTER_CONTROLS_ENABLED, true)

    fun setClusterControlsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLUSTER_CONTROLS_ENABLED, enabled).apply()
    }

    fun isNativeSmsEnabled(): Boolean = prefs.getBoolean(KEY_NATIVE_SMS_ENABLED, true)

    fun setNativeSmsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NATIVE_SMS_ENABLED, enabled).apply()
    }

    fun isAutoSmsOnRejectEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SMS_ON_REJECT, false)

    fun setAutoSmsOnRejectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SMS_ON_REJECT, enabled).apply()
    }

    fun getQuickReplySmsText(): String {
        val raw = prefs.getString(KEY_QUICK_REPLY_SMS, DEFAULT_QUICK_REPLY_SMS)?.trim()
        return if (raw.isNullOrEmpty()) DEFAULT_QUICK_REPLY_SMS else raw
    }

    fun setQuickReplySmsText(text: String) {
        val cleaned = text.trim()
        prefs.edit().apply {
            if (cleaned.isEmpty()) remove(KEY_QUICK_REPLY_SMS)
            else putString(KEY_QUICK_REPLY_SMS, cleaned)
        }.apply()
    }

    fun getHomeNavigationQuery(): String {
        return prefs.getString(KEY_HOME_NAV_QUERY, "")?.trim().orEmpty()
    }

    fun setHomeNavigationQuery(query: String) {
        val cleaned = query.trim()
        prefs.edit().apply {
            if (cleaned.isEmpty()) remove(KEY_HOME_NAV_QUERY)
            else putString(KEY_HOME_NAV_QUERY, cleaned)
        }.apply()
    }

    fun getPreferredMediaPackage(): String {
        return prefs.getString(KEY_PREFERRED_MEDIA_PKG, "")?.trim().orEmpty()
    }

    fun setPreferredMediaPackage(packageName: String) {
        val cleaned = packageName.trim()
        prefs.edit().apply {
            if (cleaned.isEmpty()) remove(KEY_PREFERRED_MEDIA_PKG)
            else putString(KEY_PREFERRED_MEDIA_PKG, cleaned)
        }.apply()
    }

    fun getRiderName(): String = prefs.getString(KEY_RIDER_NAME, "")?.trim().orEmpty()

    fun setRiderName(name: String) {
        val cleaned = name.trim()
        prefs.edit().apply {
            if (cleaned.isEmpty()) remove(KEY_RIDER_NAME)
            else putString(KEY_RIDER_NAME, cleaned)
        }.apply()
    }

    fun getOdometerMeters(): Double {
        return prefs.getString(KEY_ODOMETER_METERS, "0")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    }

    fun setOdometerMeters(meters: Double) {
        val safe = meters.coerceAtLeast(0.0)
        prefs.edit().putString(KEY_ODOMETER_METERS, safe.toString()).apply()
    }

    fun addOdometerMeters(deltaMeters: Double) {
        if (deltaMeters <= 0.0) return
        val nextTotal = getOdometerMeters() + deltaMeters
        val nextAdded = getGpsAddedMetersSinceSync() + deltaMeters
        prefs.edit()
            .putString(KEY_ODOMETER_METERS, nextTotal.toString())
            .putString(KEY_GPS_ADDED_METERS, nextAdded.toString())
            .apply()
    }

    fun getGpsAddedMetersSinceSync(): Double {
        return prefs.getString(KEY_GPS_ADDED_METERS, "0")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    }

    fun resetGpsAddedMetersSinceSync() {
        prefs.edit().putString(KEY_GPS_ADDED_METERS, "0").apply()
    }

    fun getOdometerCalibrationFactor(): Double {
        val parsed = prefs.getString(KEY_ODOMETER_CALIBRATION_FACTOR, "1.0")?.toDoubleOrNull() ?: 1.0
        return if (parsed < 0.5) 0.5 else if (parsed > 2.0) 2.0 else parsed
    }

    fun setOdometerCalibrationFactor(factor: Double) {
        val safe = if (factor < 0.5) 0.5 else if (factor > 2.0) 2.0 else factor
        prefs.edit().putString(KEY_ODOMETER_CALIBRATION_FACTOR, safe.toString()).apply()
    }

    fun isRoundaboutCounterClockwise(): Boolean {
        val lhtCountries = setOf("IN", "GB", "UK", "JP", "AU", "NZ", "ZA", "TH", "ID", "MY", "SG", "IE")
        val country = Locale.getDefault().country.uppercase(Locale.US)
        val defaultCcw = !lhtCountries.contains(country)
        return prefs.getBoolean(KEY_ROUNDABOUT_COUNTER_CLOCKWISE, defaultCcw)
    }

    fun setRoundaboutCounterClockwise(counterClockwise: Boolean) {
        prefs.edit().putBoolean(KEY_ROUNDABOUT_COUNTER_CLOCKWISE, counterClockwise).apply()
    }

    fun getLastServiceDateMs(): Long = prefs.getLong(KEY_LAST_SERVICE_DATE_MS, 0L)

    fun setLastServiceDateMs(ms: Long) {
        prefs.edit().putLong(KEY_LAST_SERVICE_DATE_MS, ms.coerceAtLeast(0L)).apply()
    }

    fun getFirstServiceMonths(): Int {
        val stored = prefs.getInt(KEY_FIRST_SERVICE_MONTHS, 1)
        return if (stored == 3) 3 else 1
    }

    fun setFirstServiceMonths(months: Int) {
        prefs.edit().putInt(KEY_FIRST_SERVICE_MONTHS, if (months == 3) 3 else 1).apply()
    }

    fun getCompletedServiceMilestones(): Set<Int> {
        val raw = prefs.getStringSet(KEY_COMPLETED_MILESTONES, emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setCompletedServiceMilestones(milestones: Set<Int>) {
        prefs.edit()
            .putStringSet(KEY_COMPLETED_MILESTONES, milestones.map { it.toString() }.toSet())
            .apply()
    }

    fun isGpsOdometerEnabled(): Boolean = prefs.getBoolean(KEY_GPS_ODOMETER_ENABLED, true)

    fun setGpsOdometerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GPS_ODOMETER_ENABLED, enabled).apply()
    }

    fun isGpsOdometerOnlyWhenConnected(): Boolean = prefs.getBoolean(KEY_GPS_ODOMETER_ONLY_CONNECTED, true)

    fun setGpsOdometerOnlyWhenConnected(onlyWhenConnected: Boolean) {
        prefs.edit().putBoolean(KEY_GPS_ODOMETER_ONLY_CONNECTED, onlyWhenConnected).apply()
    }

    fun getLastNotifiedMilestoneKm(): Int = prefs.getInt(KEY_LAST_NOTIFIED_MILESTONE_KM, -1)

    fun getLastNotifiedTier(): String = prefs.getString(KEY_LAST_NOTIFIED_TIER, "") ?: ""

    fun setLastNotifiedAlert(milestoneKm: Int, tier: String) {
        prefs.edit()
            .putInt(KEY_LAST_NOTIFIED_MILESTONE_KM, milestoneKm)
            .putString(KEY_LAST_NOTIFIED_TIER, tier)
            .apply()
    }

    fun clearMaintenanceAlertHistory() {
        prefs.edit()
            .remove(KEY_LAST_NOTIFIED_MILESTONE_KM)
            .remove(KEY_LAST_NOTIFIED_TIER)
            .apply()
    }
}
