package com.custom.bajajbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class NavigationSnapshot(
    val isActive: Boolean,
    val distanceMeters: Int,
    val totalDistanceMeters: Int,
    val maneuverCode: Byte,
    val streetText: String,
    val roundaboutExit: Int,
    val sourceLabel: String,
    val isArrived: Boolean = false
)

enum class SystemAliasResult {
    APPLIED,
    CLEARED,
    NEEDS_ASSOCIATION,
    UNSUPPORTED,
    FAILED
}

@SuppressLint("MissingPermission")
class BajajBleManager(
    private val context: Context,
    private val preferences: AppPreferences = AppPreferences(context),
    private val maintenanceManager: MaintenanceManager? = null
) {

    private enum class GattOpType {
        WRITE,
        READ
    }

    private data class GattOp(
        val type: GattOpType,
        val uuid: UUID,
        val data: ByteArray = ByteArray(0)
    )

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var activeScanCallback: ScanCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentDeviceAddress: String? = null
    private var currentDeviceName: String? = null
    private var manualDisconnect = false
    private var reconnectAttempt = 0
    private var isConnectingState = false
    private var isPassiveWaitingState = false
    private var isScanningState = false
    private var isMtuReady = false
    private var isPeriodicRunning = false
    private var isGattBusy = false
    private var gattOpCounter = 0
    private var lastWatchdogCounter = -1
    private var lastUiBatt = -1
    private var lastUiSig = -1
    private var lastUiCall = ""

    private val gattQueue = ArrayDeque<GattOp>()

    private var heartbeatCounter = 0
    private var currentBattery = 85
    private var currentSignal = 4
    private var isHeadsetConnected = false
    private var currentVolumeLevel = 10

    // Call state
    private var currentCallState = BajajProtocol.CALL_NO_CALL
    private var currentCallerName = ""
    private var telephonyCallState = TelephonyManager.CALL_STATE_IDLE
    private var telephonyCallerName = ""
    private var lastIncomingPhoneNumber = ""
    private var wasRingingState = false
    private var wasOffhookState = false
    private var isOutgoingTelephonyCall = false
    private var activeCallEndPulse = false

    private var notificationCallActive = false
    private var notificationCallState = BajajProtocol.CALL_NO_CALL
    private var notificationCallerName = ""
    private var lastCallDebugSummary = "Livre"
    private var lastStatusMessage = "Desconectado"

    // Application-level retained navigation state (updated even when activity is destroyed)
    private var isNavigating = false
    private var isNavArrived = false
    private var lastDistanceMeters = 0
    private var lastTotalDistanceMeters = 0
    private var lastRoundaboutExit = 0
    private var lastManeuverCode = BajajProtocol.ARROW_STRAIGHT_SOLID
    private var lastNavText = "Aguardando rota no Google Maps"
    private var lastNavSource = "Inativo"
    private var lastEtaSeconds = -1
    private var lastEtaHours = -1
    private var lastEtaMinutes = -1
    private var lastIsAm: Boolean? = null

    // UI-observable diagnostics
    private var listenerConnected = false
    private var lastMapsCaptureSummary = "Nenhuma rota capturada"
    private var lastAlertDispatchSummary = "Nenhum alerta enviado"
    private var lastBleDebugEvent = "Aguardando conexão com o painel"
    private var lastControlDebugSummary = "Nenhum comando recebido"
    private var lastNavBleWriteMs = 0L
    private var lastNavBleSentCode: Byte = 0
    private var lastNavBleSentDist = -1

    // Handlebar cluster controls (CHAR_CONTROLS 0A10)
    private var controlsPollCount = 0L
    private var lastCtrlVolume = -1
    private var lastCtrlCallAccept = 0
    private var lastCtrlCallReject = 0
    private var lastCtrlCallRejectWithSms = 0
    private var lastCtrlPagePlaylist = -1
    private var lastCtrlTakeMeHome = -1
    private var lastCtrlResumeSong = -1
    private var lastCtrlPauseSong = -1
    private var lastCtrlSkipToNext = -1
    private var lastCtrlSkipToPrev = -1
    private var lastCtrlStopSong = -1
    private var lastCtrlLaunchMediaPlayer = -1

    // Incoming SMS deduplication
    private var lastNativeSmsSig = ""
    private var lastNativeSmsTimeMs = 0L

    private var isNavLocationActive = false
    private var isOdometerLocationActive = false

    private fun syncForegroundLocationMode() {
        BajajForegroundService.updateLocationTrackingMode(
            context,
            isNavLocationActive || isOdometerLocationActive
        )
    }

    private val hybridNavigator: MapsHybridNavigator by lazy {
        MapsHybridNavigator(
            context = context,
            mainHandler = mainHandler,
            onOutput = { out -> applyHybridNavigationOutput(out) },
            onAutoStopped = { stopNavigation() },
            onGpsTrackingChanged = { active ->
                isNavLocationActive = active
                syncForegroundLocationMode()
            }
        )
    }

    // Media playback (CHAR_MEDIA 0610)
    private var currentSongTitle = ""
    private var currentArtist = ""
    private var currentAlbum = ""
    private var currentMusicPlayStatus: Byte = BajajProtocol.PLAY_STATUS_NONE
    private var isMusicPlaying = false

    // UI callbacks
    var onStatusChanged: ((String) -> Unit)? = null
    var onDataRefreshed: ((Int, Int, String) -> Unit)? = null
    var onNavigationUpdated: ((NavigationSnapshot) -> Unit)? = null
    var onDiagnosticsUpdated: (() -> Unit)? = null
    var onConnectionMetaChanged: (() -> Unit)? = null
    var onScanStateChanged: ((Boolean) -> Unit)? = null

    companion object {
        private const val TAG = "BajajBle"
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val SCAN_TIMEOUT_MS = 12_000L
        val CHAR_TBT: UUID = UUID.fromString("0110676e-6972-6565-6e69-676e4543544f")
        val CHAR_STATUS: UUID = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f")
        val CHAR_CALLS: UUID = UUID.fromString("0310676e-6972-6565-6e69-676e4543544f")
        val CHAR_ALERTS: UUID = UUID.fromString("0410676e-6972-6565-6e69-676e4543544f")
        val CHAR_MEDIA: UUID = UUID.fromString("0610676e-6972-6565-6e69-676e4543544f")
        val CHAR_CONTROLS: UUID = UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f")
    }

    fun isConnected(): Boolean = bluetoothGatt != null && isMtuReady

    fun isConnecting(): Boolean = isConnectingState

    fun isPassiveWaiting(): Boolean = isPassiveWaitingState

    fun isScanning(): Boolean = isScanningState

    fun getConnectedAddress(): String? = currentDeviceAddress

    fun getConnectedName(): String? = currentDeviceName

    fun getStatusMessage(): String = lastStatusMessage

    fun getCurrentBattery(): Int = currentBattery

    fun getCurrentSignal(): Int = currentSignal

    fun getHeadsetConnected(): Boolean = isHeadsetConnected

    fun getVolumeLevel(): Int = currentVolumeLevel

    fun getCurrentCallInfo(): String =
        if (currentCallState != BajajProtocol.CALL_NO_CALL && currentCallState != BajajProtocol.CALL_END) {
            currentCallerName
        } else {
            "Livre"
        }

    fun getNavigationSnapshot(): NavigationSnapshot {
        return NavigationSnapshot(
            isActive = isNavigating,
            distanceMeters = lastDistanceMeters,
            totalDistanceMeters = lastTotalDistanceMeters,
            maneuverCode = lastManeuverCode,
            streetText = lastNavText,
            roundaboutExit = lastRoundaboutExit,
            sourceLabel = lastNavSource,
            isArrived = isNavArrived
        )
    }

    fun isNotificationListenerConnected(): Boolean = listenerConnected

    fun getLastMapsCaptureSummary(): String = lastMapsCaptureSummary

    fun getLastAlertDispatchSummary(): String = lastAlertDispatchSummary

    fun getLastBleDebugEvent(): String = lastBleDebugEvent

    fun getLastCallDebugSummary(): String = lastCallDebugSummary

    fun getLastControlDebugSummary(): String = lastControlDebugSummary

    fun getMusicSummary(): String {
        if (currentSongTitle.isBlank() || currentMusicPlayStatus == BajajProtocol.PLAY_STATUS_NONE) {
            return "Nenhuma mídia ativa"
        }
        val stateLabel = if (currentMusicPlayStatus == BajajProtocol.PLAY_STATUS_PLAY) "Tocando" else "Pausado"
        return if (currentArtist.isNotBlank()) {
            "$stateLabel: $currentSongTitle — $currentArtist"
        } else {
            "$stateLabel: $currentSongTitle"
        }
    }

    fun refreshDisplayNames() {
        val addr = currentDeviceAddress ?: preferences.getPinnedAddress()
        val displayName = preferences.getDisplayName(addr, currentDeviceName)
        if (isConnected()) {
            val newStatus = "Conectado a $displayName"
            updateStatus(newStatus)
            BajajForegroundService.updateStatus(context, newStatus, keepWakeLock = true)
        } else if (isConnectingState) {
            val newStatus = "Conectando a $displayName..."
            updateStatus(newStatus)
            BajajForegroundService.updateStatus(context, newStatus, keepWakeLock = false)
        } else if (isPassiveWaitingState) {
            val newStatus = "Aguardando $displayName..."
            updateStatus(newStatus)
            BajajForegroundService.updateStatus(context, newStatus, keepWakeLock = false)
        } else {
            mainHandler.post { onConnectionMetaChanged?.invoke() }
        }
    }

    fun setNotificationListenerConnected(connected: Boolean) {
        listenerConnected = connected
        mainHandler.post { onDiagnosticsUpdated?.invoke() }
    }

    fun pushCurrentStateToUi() {
        mainHandler.post {
            onStatusChanged?.invoke(lastStatusMessage)
            onDataRefreshed?.invoke(currentBattery, currentSignal, getCurrentCallInfo())
            onNavigationUpdated?.invoke(getNavigationSnapshot())
            onDiagnosticsUpdated?.invoke()
            onConnectionMetaChanged?.invoke()
            onScanStateChanged?.invoke(isScanningState)
        }
    }

    // =========================================================================
    // COMPANION DEVICE MANAGER & BLUETOOTH SYSTEM ALIAS (API 30+)
    // =========================================================================
    private fun isCompanionAssociated(cdm: CompanionDeviceManager, address: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                if (cdm.myAssociations.any {
                        it.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
                    }
                ) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return try {
            @Suppress("DEPRECATION")
            cdm.associations.any { it.equals(address, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    fun tryApplySystemBluetoothAlias(address: String, alias: String): SystemAliasResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SystemAliasResult.UNSUPPORTED
        }
        if (!hasBleConnectPermission()) {
            return SystemAliasResult.FAILED
        }
        val adapter = bluetoothAdapter ?: return SystemAliasResult.FAILED
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Exception) {
            return SystemAliasResult.FAILED
        }
        val targetAlias: String? = alias.trim().takeIf { it.isNotEmpty() }
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)

        return try {
            val method = BluetoothDevice::class.java.getMethod("setAlias", String::class.java)
            val result = method.invoke(device, targetAlias)
            val ok = when (result) {
                is Boolean -> result
                is Int -> result == BluetoothStatusCodes.SUCCESS
                else -> false
            }
            if (ok) {
                if (targetAlias == null) SystemAliasResult.CLEARED else SystemAliasResult.APPLIED
            } else if (cdm != null && !isCompanionAssociated(cdm, address)) {
                SystemAliasResult.NEEDS_ASSOCIATION
            } else {
                SystemAliasResult.FAILED
            }
        } catch (ite: InvocationTargetException) {
            val cause = ite.targetException
            if (cause is SecurityException && cdm != null && !isCompanionAssociated(cdm, address)) {
                SystemAliasResult.NEEDS_ASSOCIATION
            } else {
                Log.w(TAG, "setAlias InvocationTargetException: ${cause?.message}")
                SystemAliasResult.FAILED
            }
        } catch (_: SecurityException) {
            if (cdm != null && !isCompanionAssociated(cdm, address)) {
                SystemAliasResult.NEEDS_ASSOCIATION
            } else {
                SystemAliasResult.FAILED
            }
        } catch (e: Exception) {
            Log.w(TAG, "setAlias falhou: ${e.message}")
            SystemAliasResult.FAILED
        }
    }

    fun requestCompanionAssociation(
        activity: Activity,
        address: String,
        onIntentSenderReady: (IntentSender) -> Unit,
        onAlreadyAssociated: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onError("Requer Android 8 ou superior")
            return
        }
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            onError("CompanionDeviceManager indisponível")
            return
        }
        if (isCompanionAssociated(cdm, address)) {
            onAlreadyAssociated()
            return
        }

        try {
            val scanFilter = ScanFilter.Builder()
                .setDeviceAddress(address.uppercase(Locale.ROOT))
                .build()
            val leFilter = BluetoothLeDeviceFilter.Builder()
                .setScanFilter(scanFilter)
                .build()
            val request = AssociationRequest.Builder()
                .setSingleDevice(true)
                .addDeviceFilter(leFilter)
                .build()

            val dispatched = AtomicBoolean(false)
            val callback = object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    if (dispatched.compareAndSet(false, true)) {
                        mainHandler.post { onIntentSenderReady(chooserLauncher) }
                    }
                }

                override fun onAssociationPending(intentSender: IntentSender) {
                    if (dispatched.compareAndSet(false, true)) {
                        mainHandler.post { onIntentSenderReady(intentSender) }
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    mainHandler.post {
                        onError(error?.toString()?.takeIf { it.isNotBlank() } ?: "Associação recusada")
                    }
                }
            }
            cdm.associate(request, callback, mainHandler)
        } catch (e: Exception) {
            onError(e.message ?: "Erro ao iniciar associação")
        }
    }

    private val gattOpTimeoutRunnable = Runnable {
        synchronized(gattQueue) {
            if (isGattBusy) {
                Log.w(TAG, "GATT op timeout; liberando fila")
                isGattBusy = false
                drainGattQueueLocked()
            }
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (bluetoothGatt != null && isMtuReady) {
                synchronized(gattQueue) {
                    if (isGattBusy && gattOpCounter == lastWatchdogCounter && gattQueue.isNotEmpty()) {
                        Log.w(TAG, "Watchdog destravando fila GATT (counter=$gattOpCounter)")
                        isGattBusy = false
                        drainGattQueueLocked()
                    }
                    lastWatchdogCounter = gattOpCounter
                }
                mainHandler.postDelayed(this, 3000)
            }
        }
    }

    private val mtuFallbackRunnable = Runnable {
        if (bluetoothGatt != null && !isMtuReady) {
            val displayName = preferences.getDisplayName(currentDeviceAddress, currentDeviceName)
            isMtuReady = true
            isConnectingState = false
            isPassiveWaitingState = false
            reconnectAttempt = 0
            maintenanceManager?.setBleConnected(true)
            updateStatus("Conectado a $displayName")
            BajajForegroundService.updateStatus(context, "Conectado a $displayName", keepWakeLock = true)
            startPeriodicLoops()
            synchronized(gattQueue) {
                drainGattQueueLocked()
            }
        }
    }

    // Envio periódico do estado do celular ao painel (1s em rota/chamada, 1.5s ocioso)
    private val statusPeriodicRunnable = object : Runnable {
        override fun run() {
            if (bluetoothGatt != null && isMtuReady) {
                sendPhoneStatus()
                if (isNavigating) {
                    sendNavigationPacket(
                        lastDistanceMeters,
                        lastManeuverCode,
                        lastNavText,
                        lastTotalDistanceMeters,
                        lastRoundaboutExit,
                        lastEtaSeconds,
                        lastEtaHours,
                        lastEtaMinutes,
                        lastIsAm
                    )
                }
                val intervalMs = if (isNavigating || isInCallAudioContext()) 1000L else 1500L
                mainHandler.postDelayed(this, intervalMs)
            }
        }
    }

    // Polling de leitura dos comandos do guidão (somente quando ativado)
    private val controlsPollRunnable = object : Runnable {
        override fun run() {
            if (bluetoothGatt != null && isMtuReady) {
                if (preferences.isClusterControlsEnabled()) {
                    enqueueRead(CHAR_CONTROLS)
                    mainHandler.postDelayed(this, 700)
                } else {
                    mainHandler.postDelayed(this, 2500)
                }
            }
        }
    }

    private val scanTimeoutRunnable = Runnable {
        if (isScanningState) {
            stopScan()
        }
    }

    private val callEndPulseRunnable = Runnable {
        activeCallEndPulse = false
        syncCallStateToCluster()
    }

    private val reconnectRunnable = Runnable {
        if (manualDisconnect) return@Runnable
        val targetAddress = preferences.getPinnedAddress() ?: currentDeviceAddress ?: return@Runnable
        val targetName = preferences.getPinnedName() ?: currentDeviceName ?: "Moto Bajaj"
        connectByAddress(targetAddress, targetName, autoConnect = true, isReconnect = true)
    }

    private val volumeContentObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri != null && !uri.toString().contains("volume", ignoreCase = true)) {
                return
            }
            val oldVol = currentVolumeLevel
            updateVolumeLevel()
            if (currentVolumeLevel != oldVol) {
                sendPhoneStatus()
                mainHandler.post { onDiagnosticsUpdated?.invoke() }
            }
        }
    }

    private val systemEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        val nextBatt = (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
                        if (nextBatt != currentBattery) {
                            currentBattery = nextBatt
                            notifyDataRefreshedIfChanged()
                        }
                    }
                }
                ACTION_VOLUME_CHANGED -> {
                    val oldVol = currentVolumeLevel
                    updateVolumeLevel()
                    if (currentVolumeLevel != oldVol) {
                        sendPhoneStatus()
                        mainHandler.post { onDiagnosticsUpdated?.invoke() }
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val oldHeadset = isHeadsetConnected
                    updateHeadsetConnectionState()
                    if (isHeadsetConnected != oldHeadset) {
                        sendPhoneStatus()
                        mainHandler.post { onDiagnosticsUpdated?.invoke() }
                    }
                }
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    @Suppress("DEPRECATION")
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    val mapped = when (stateStr) {
                        TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                        TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                        else -> null
                    }
                    if (mapped != null) {
                        handleTelephonyStateTransition(mapped, number)
                    }
                }
                Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                    handleIncomingSmsBroadcast(intent)
                }
            }
        }
    }

    init {
        maintenanceManager?.onLocationTrackingModeChanged = { active ->
            isOdometerLocationActive = active
            syncForegroundLocationMode()
        }
        setupSystemReceivers()
    }

    private fun setupSystemReceivers() {
        mainHandler.post {
            readRealBattery()
            updateVolumeLevel()
            updateHeadsetConnectionState()
            setupTelephonyListener()
            registerBroadcastAndVolumeObservers()
        }
    }

    private fun registerBroadcastAndVolumeObservers() {
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeContentObserver
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao registrar ContentObserver de volume: ${e.message}")
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ACTION_VOLUME_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(systemEventsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(systemEventsReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao registrar systemEventsReceiver: ${e.message}")
        }
    }

    private fun hasBleConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun notifyDataRefreshedIfChanged() {
        val callInfo = getCurrentCallInfo()
        if (currentBattery == lastUiBatt && currentSignal == lastUiSig && callInfo == lastUiCall) {
            return
        }
        lastUiBatt = currentBattery
        lastUiSig = currentSignal
        lastUiCall = callInfo
        mainHandler.post {
            onDataRefreshed?.invoke(currentBattery, currentSignal, callInfo)
        }
    }

    private fun readRealBattery() {
        try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                currentBattery = (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
            }
            notifyDataRefreshedIfChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler bateria", e)
        }
    }

    private fun isInCallAudioContext(): Boolean {
        return currentCallState == BajajProtocol.CALL_ACTIVE ||
            currentCallState == BajajProtocol.CALL_INCOMING ||
            currentCallState == BajajProtocol.CALL_OUTGOING
    }

    private fun updateVolumeLevel() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val stream = if (isInCallAudioContext()) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            val cur = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            currentVolumeLevel = ((cur * 10.0) / max).roundToInt().coerceIn(0, 10)
        } catch (_: Exception) {
        }
    }

    private fun updateHeadsetConnectionState() {
        if (!hasBleConnectPermission()) {
            isHeadsetConnected = false
            return
        }
        try {
            val adapter = bluetoothAdapter
            isHeadsetConnected = adapter != null &&
                adapter.isEnabled &&
                adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (_: Exception) {
            isHeadsetConnected = false
        }
    }

    private fun setupTelephonyListener() {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tm.registerTelephonyCallback(
                    context.mainExecutor,
                    object : TelephonyCallback(),
                        TelephonyCallback.SignalStrengthsListener,
                        TelephonyCallback.CallStateListener {
                        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                            currentSignal = signalStrength.level.coerceIn(0, 4)
                            notifyDataRefreshedIfChanged()
                        }

                        override fun onCallStateChanged(state: Int) {
                            handleTelephonyStateTransition(state, null)
                        }
                    }
                )
            } else {
                @Suppress("DEPRECATION")
                tm.listen(object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                        super.onSignalStrengthsChanged(signalStrength)
                        currentSignal = (signalStrength?.level ?: 3).coerceIn(0, 4)
                        notifyDataRefreshedIfChanged()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        super.onCallStateChanged(state, phoneNumber)
                        handleTelephonyStateTransition(state, phoneNumber)
                    }
                }, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro telephony listener", e)
        }
    }

    private fun handleTelephonyStateTransition(state: Int, rawPhoneNumber: String?) {
        val cleanedNum = rawPhoneNumber?.trim().orEmpty()
        if (cleanedNum.isNotBlank()) {
            lastIncomingPhoneNumber = cleanedNum
        }

        val prevState = telephonyCallState
        telephonyCallState = state

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                mainHandler.removeCallbacks(callEndPulseRunnable)
                activeCallEndPulse = false
                wasRingingState = true
                wasOffhookState = false
                isOutgoingTelephonyCall = false

                val numForLookup = cleanedNum.ifBlank { lastIncomingPhoneNumber }
                val contactName = resolveContactName(numForLookup)
                val candidate = contactName?.takeIf { it.isNotBlank() }
                    ?: numForLookup.takeIf { it.isNotBlank() }
                if (!candidate.isNullOrBlank()) {
                    telephonyCallerName = candidate
                } else if (telephonyCallerName.isBlank()) {
                    telephonyCallerName = "Chamada recebida"
                }
                lastCallDebugSummary = "Telefone (tocando): $telephonyCallerName"
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                mainHandler.removeCallbacks(callEndPulseRunnable)
                activeCallEndPulse = false
                if (!wasRingingState && prevState != TelephonyManager.CALL_STATE_OFFHOOK) {
                    isOutgoingTelephonyCall = true
                }
                wasRingingState = false
                wasOffhookState = true

                val numForLookup = cleanedNum.ifBlank { lastIncomingPhoneNumber }
                if (numForLookup.isNotBlank()) {
                    val contactName = resolveContactName(numForLookup)
                    val candidate = contactName?.takeIf { it.isNotBlank() } ?: numForLookup
                    if (telephonyCallerName.isBlank() || telephonyCallerName == "Chamada recebida") {
                        telephonyCallerName = candidate
                    }
                }
                if (telephonyCallerName.isBlank()) {
                    telephonyCallerName = notificationCallerName.ifBlank {
                        if (isOutgoingTelephonyCall) "Discando..." else "Em ligação"
                    }
                }
                val modeStr = if (isOutgoingTelephonyCall) "efetuando" else "ativa"
                lastCallDebugSummary = "Telefone ($modeStr): $telephonyCallerName"
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                val missedCallOccurred = wasRingingState && !wasOffhookState
                val endedActiveCall = wasOffhookState

                if (missedCallOccurred) {
                    val missedName = pickBestCallerName(telephonyCallerName, notificationCallerName)
                        .ifBlank { lastIncomingPhoneNumber.ifBlank { "Chamada perdida" } }
                    val missedNum = lastIncomingPhoneNumber.ifBlank { "Desconhecido" }
                    if (isConnected()) {
                        sendMissedCall(missedName, missedNum)
                    }
                    lastCallDebugSummary = "Perdida: $missedName"
                } else if (endedActiveCall) {
                    activeCallEndPulse = true
                    mainHandler.removeCallbacks(callEndPulseRunnable)
                    mainHandler.postDelayed(callEndPulseRunnable, 1200L)
                }

                wasRingingState = false
                wasOffhookState = false
                isOutgoingTelephonyCall = false
                telephonyCallerName = ""
            }
        }
        syncCallStateToCluster()
    }

    private fun handleIncomingSmsBroadcast(intent: Intent) {
        if (!preferences.isNativeSmsEnabled()) return
        if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            var sender = ""
            val bodyBuilder = StringBuilder()
            for (sms in messages) {
                if (sms == null) continue
                if (sender.isEmpty()) {
                    sender = sms.displayOriginatingAddress?.trim().orEmpty()
                }
                sms.displayMessageBody?.let { bodyBuilder.append(it) }
            }
            val body = bodyBuilder.toString().trim()
            if (sender.isEmpty() && body.isEmpty()) return

            val contact = resolveContactName(sender) ?: sender.ifBlank { "SMS" }
            val sig = "$sender|$body"
            val now = System.currentTimeMillis()
            if (sig == lastNativeSmsSig && (now - lastNativeSmsTimeMs) < 5000L) {
                return
            }
            lastNativeSmsSig = sig
            lastNativeSmsTimeMs = now

            val payload = if (body.isNotEmpty()) "$contact: $body" else contact
            val truncated = String(
                BajajProtocol.truncateUtf8(cleanForClusterText(payload), BajajProtocol.MAX_ALERT_BYTES),
                Charsets.UTF_8
            )
            sendAlertNotification(BajajProtocol.ALERT_SMS, truncated)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao processar SMS_RECEIVED: ${e.message}")
        }
    }

    fun updateNotificationCallState(
        active: Boolean,
        callState: Int,
        callerCandidate: String,
        sourceLabel: String
    ) {
        notificationCallActive = active
        if (active) {
            notificationCallState = callState
            val cleaned = callerCandidate.trim()
            if (cleaned.isNotEmpty()) {
                notificationCallerName = cleaned
            } else if (notificationCallerName.isBlank()) {
                notificationCallerName = if (sourceLabel.isNotBlank()) "Ligação $sourceLabel" else "Ligação"
            }
            val modeTxt = if (callState == BajajProtocol.CALL_ACTIVE) "ativa" else "tocando"
            lastCallDebugSummary = "$sourceLabel ($modeTxt): $notificationCallerName"
        } else {
            notificationCallState = BajajProtocol.CALL_NO_CALL
            notificationCallerName = ""
        }
        syncCallStateToCluster()
    }

    private fun syncCallStateToCluster() {
        val resolvedState: Int
        val resolvedName: String

        when {
            activeCallEndPulse &&
                telephonyCallState == TelephonyManager.CALL_STATE_IDLE &&
                !notificationCallActive -> {
                resolvedState = BajajProtocol.CALL_END
                resolvedName = ""
                lastCallDebugSummary = "Chamada encerrada"
            }
            telephonyCallState == TelephonyManager.CALL_STATE_RINGING -> {
                resolvedState = BajajProtocol.CALL_INCOMING
                resolvedName = pickBestCallerName(telephonyCallerName, notificationCallerName)
                    .ifBlank { "Chamada recebida" }
            }
            telephonyCallState == TelephonyManager.CALL_STATE_OFFHOOK -> {
                resolvedState = if (isOutgoingTelephonyCall && notificationCallState != BajajProtocol.CALL_ACTIVE) {
                    BajajProtocol.CALL_OUTGOING
                } else {
                    BajajProtocol.CALL_ACTIVE
                }
                resolvedName = pickBestCallerName(telephonyCallerName, notificationCallerName)
                    .ifBlank { if (resolvedState == BajajProtocol.CALL_OUTGOING) "Discando..." else "Em ligação" }
            }
            notificationCallActive -> {
                resolvedState = notificationCallState
                resolvedName = notificationCallerName.ifBlank { "Ligação" }
            }
            else -> {
                resolvedState = BajajProtocol.CALL_NO_CALL
                resolvedName = ""
                lastCallDebugSummary = "Livre"
            }
        }

        sendPhoneStatus(callState = resolvedState, caller = resolvedName)
        mainHandler.post { onDiagnosticsUpdated?.invoke() }
    }

    private fun pickBestCallerName(primary: String, secondary: String): String {
        val p = primary.trim()
        val s = secondary.trim()
        val pIsPlaceholder = p.isEmpty() || p == "Chamada recebida" || p == "Em ligação" || p == "Discando..."
        val sIsPlaceholder = s.isEmpty() || s == "Chamada recebida" || s == "Em ligação" || s == "Discando..."
        if (pIsPlaceholder && !sIsPlaceholder) return s
        if (!pIsPlaceholder && sIsPlaceholder) return p
        val pLooksNumeric = p.count { it.isDigit() } >= 6 && p.all { it.isDigit() || it in setOf('+', '-', ' ', '(', ')') }
        val sLooksNumeric = s.count { it.isDigit() } >= 6 && s.all { it.isDigit() || it in setOf('+', '-', ' ', '(', ')') }
        if (pLooksNumeric && !sLooksNumeric && s.isNotEmpty()) return s
        return p.ifEmpty { s }
    }

    fun resolveContactNameIfPossible(phoneNumber: String?): String? = resolveContactName(phoneNumber)

    private fun resolveContactName(phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun nudgeControlsPolling() {
        if (bluetoothGatt != null && isMtuReady) {
            mainHandler.removeCallbacks(controlsPollRunnable)
            mainHandler.post(controlsPollRunnable)
        }
    }

    private fun startPeriodicLoops() {
        if (isPeriodicRunning) return
        isPeriodicRunning = true
        mainHandler.post {
            readRealBattery()
            updateVolumeLevel()
            updateHeadsetConnectionState()
            mainHandler.removeCallbacks(statusPeriodicRunnable)
            mainHandler.removeCallbacks(controlsPollRunnable)
            mainHandler.removeCallbacks(watchdogRunnable)
            mainHandler.post(statusPeriodicRunnable)
            mainHandler.postDelayed(controlsPollRunnable, 250)
            mainHandler.postDelayed(watchdogRunnable, 3000)
        }
    }

    private fun stopPeriodicLoops() {
        isPeriodicRunning = false
        mainHandler.removeCallbacks(statusPeriodicRunnable)
        mainHandler.removeCallbacks(controlsPollRunnable)
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val cb = activeScanCallback
        if (scanner != null && cb != null) {
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
        }
        activeScanCallback = null
        if (isScanningState) {
            isScanningState = false
            mainHandler.post { onScanStateChanged?.invoke(false) }
            if (!isConnected() && !isConnectingState) {
                val pinnedAddr = preferences.getPinnedAddress()
                if (isPassiveWaitingState && pinnedAddr != null) {
                    val pinnedName = preferences.getDisplayName(pinnedAddr, preferences.getPinnedName())
                    updateStatus("Aguardando $pinnedName...")
                } else if (lastStatusMessage.startsWith("Buscando")) {
                    updateStatus("Desconectado")
                }
            }
        }
    }

    fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        val adapter = bluetoothAdapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            updateStatus("Bluetooth desativado")
            return
        }

        stopScan()
        isScanningState = true
        mainHandler.post { onScanStateChanged?.invoke(true) }
        if (!isConnected() && !isConnectingState && !isPassiveWaitingState) {
            updateStatus("Buscando painel...")
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.device?.let { device ->
                    onDeviceFound(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan BLE falhou (código $errorCode)")
                mainHandler.removeCallbacks(scanTimeoutRunnable)
                activeScanCallback = null
                isScanningState = false
                mainHandler.post { onScanStateChanged?.invoke(false) }
                recordDebug("Falha temporária ao buscar o painel")
                if (!isConnected() && !isPassiveWaitingState) {
                    updateStatus("Falha ao buscar painel")
                }
            }
        }
        activeScanCallback = callback
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao iniciar scan BLE: ${e.message}")
            activeScanCallback = null
            isScanningState = false
            mainHandler.post { onScanStateChanged?.invoke(false) }
            recordDebug("Não foi possível iniciar a busca")
            if (!isPassiveWaitingState) {
                updateStatus("Falha ao iniciar busca")
            }
        }
    }

    fun tryAutoConnectPinned(preferPassiveAutoConnect: Boolean = true): Boolean {
        val pinnedAddr = preferences.getPinnedAddress() ?: return false
        if (isConnected() && pinnedAddr.equals(currentDeviceAddress, ignoreCase = true)) {
            return true
        }
        if ((isConnectingState || isPassiveWaitingState) && pinnedAddr.equals(currentDeviceAddress, ignoreCase = true)) {
            return true
        }
        val pinnedName = preferences.getDisplayName(pinnedAddr, preferences.getPinnedName())
        connectByAddress(pinnedAddr, pinnedName, autoConnect = preferPassiveAutoConnect, isReconnect = false)
        return true
    }

    fun connect(device: BluetoothDevice) {
        val rawName = device.name?.takeIf { it.isNotBlank() } ?: "Moto Bajaj"
        val displayName = preferences.getDisplayName(device.address, rawName)
        connectInternal(device, displayName, autoConnect = false, isReconnect = false)
    }

    fun connectByAddress(
        address: String,
        displayName: String,
        autoConnect: Boolean = false,
        isReconnect: Boolean = false
    ) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("Bluetooth desativado")
            return
        }
        if (!hasBleConnectPermission()) {
            updateStatus("Permissões Bluetooth pendentes")
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            connectInternal(device, displayName, autoConnect, isReconnect)
        } catch (e: Exception) {
            Log.w(TAG, "Endereço inválido ($address): ${e.message}")
            recordDebug("Dispositivo selecionado inválido")
            updateStatus("Erro ao conectar à moto")
        }
    }

    private fun resetControlsBaseline() {
        controlsPollCount = 0L
        lastCtrlVolume = -1
        lastCtrlCallAccept = 0
        lastCtrlCallReject = 0
        lastCtrlCallRejectWithSms = 0
        lastCtrlPagePlaylist = -1
        lastCtrlTakeMeHome = -1
        lastCtrlResumeSong = -1
        lastCtrlPauseSong = -1
        lastCtrlSkipToNext = -1
        lastCtrlSkipToPrev = -1
        lastCtrlStopSong = -1
        lastCtrlLaunchMediaPlayer = -1
    }

    private fun connectInternal(
        device: BluetoothDevice,
        displayName: String,
        autoConnect: Boolean,
        isReconnect: Boolean
    ) {
        if (!hasBleConnectPermission()) {
            updateStatus("Permissões Bluetooth pendentes")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("Bluetooth desativado")
            return
        }

        // Nunca escanear durante abertura de link GATT (causa quedas no Android/Samsung)
        stopScan()
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(mtuFallbackRunnable)
        mainHandler.removeCallbacks(gattOpTimeoutRunnable)

        manualDisconnect = false
        isPassiveWaitingState = autoConnect
        isConnectingState = !autoConnect
        currentDeviceAddress = device.address
        currentDeviceName = displayName
        resetControlsBaseline()

        synchronized(gattQueue) {
            gattQueue.clear()
            isGattBusy = false
            isMtuReady = false
        }
        stopPeriodicLoops()
        safeCloseGatt()

        val label = when {
            autoConnect -> "Aguardando $displayName..."
            isReconnect -> "Reconectando a $displayName..."
            else -> "Conectando a $displayName..."
        }
        updateStatus(label)
        Log.d(TAG, "Iniciando connectGatt(${device.address}, autoConnect=$autoConnect)")
        recordDebug(if (autoConnect) "Aguardando painel ligar..." else "Conectando ao painel...")

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, autoConnect, gattCallback)
        }

        if (bluetoothGatt == null) {
            isConnectingState = false
            isPassiveWaitingState = false
            updateStatus("Falha ao conectar ao painel")
            scheduleReconnectIfNeeded()
        } else {
            BajajForegroundService.updateStatus(context, label, keepWakeLock = false)
        }
    }

    fun disconnect(userInitiated: Boolean = true) {
        manualDisconnect = userInitiated
        isConnectingState = false
        isPassiveWaitingState = false
        reconnectAttempt = 0
        resetControlsBaseline()
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(mtuFallbackRunnable)
        mainHandler.removeCallbacks(gattOpTimeoutRunnable)
        stopPeriodicLoops()

        synchronized(gattQueue) {
            gattQueue.clear()
            isGattBusy = false
            isMtuReady = false
        }

        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {
        }
        safeCloseGatt()

        maintenanceManager?.setBleConnected(false)
        updateStatus("Desconectado")
        recordDebug(if (userInitiated) "Desconectado manualmente" else "Conexão encerrada")
        BajajForegroundService.updateStatus(context, "Desconectado do painel", keepWakeLock = false)
    }

    private fun safeCloseGatt() {
        val gatt = bluetoothGatt ?: return
        bluetoothGatt = null
        try {
            gatt.close()
        } catch (_: Exception) {
        }
    }

    private fun scheduleReconnectIfNeeded() {
        if (manualDisconnect) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || !hasBleConnectPermission()) return

        val targetAddr = preferences.getPinnedAddress() ?: currentDeviceAddress ?: return
        val delayMs = when (reconnectAttempt) {
            0 -> 4_000L
            1 -> 10_000L
            2 -> 25_000L
            else -> 60_000L
        }
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delayMs)

        isPassiveWaitingState = true
        isConnectingState = false
        val targetName = preferences.getDisplayName(targetAddr, currentDeviceName ?: preferences.getPinnedName() ?: targetAddr)
        updateStatus("Aguardando $targetName...")
        BajajForegroundService.updateStatus(context, "Aguardando $targetName...", keepWakeLock = false)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt && bluetoothGatt != null) {
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                reconnectAttempt = 0
                isPassiveWaitingState = false
                isConnectingState = true
                resetControlsBaseline()
                updateStatus("Preparando painel...")
                Log.d(TAG, "GATT conectado; descobrindo serviços")
                recordDebug("Painel conectado; preparando dados")
                mainHandler.post {
                    if (!gatt.discoverServices()) {
                        Log.w(TAG, "discoverServices retornou false")
                        recordDebug("Falha ao preparar serviços do painel")
                        disconnect(userInitiated = false)
                        scheduleReconnectIfNeeded()
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val wasReady = isMtuReady
                mainHandler.removeCallbacks(mtuFallbackRunnable)
                mainHandler.removeCallbacks(gattOpTimeoutRunnable)
                stopPeriodicLoops()
                resetControlsBaseline()
                synchronized(gattQueue) {
                    gattQueue.clear()
                    isGattBusy = false
                    isMtuReady = false
                }
                isConnectingState = false
                isPassiveWaitingState = false
                safeCloseGatt()
                maintenanceManager?.setBleConnected(false)

                Log.d(TAG, "GATT desconectado (status=$status, newState=$newState)")
                recordDebug(
                    when {
                        manualDisconnect -> "Desconectado manualmente"
                        wasReady -> "Conexão encerrada com o painel"
                        else -> "Aguardando painel ligar"
                    }
                )
                if (manualDisconnect) {
                    updateStatus("Desconectado")
                    BajajForegroundService.updateStatus(context, "Desconectado do painel", keepWakeLock = false)
                } else if (preferences.getPinnedAddress() != null || currentDeviceAddress != null) {
                    scheduleReconnectIfNeeded()
                } else {
                    updateStatus("Desconectado")
                    BajajForegroundService.updateStatus(context, "Desconectado do painel", keepWakeLock = false)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val foundChars = listOf(CHAR_TBT, CHAR_STATUS, CHAR_CALLS, CHAR_ALERTS, CHAR_MEDIA, CHAR_CONTROLS)
                    .count { findCharacteristic(gatt, it) != null }
                Log.d(TAG, "Serviços descobertos ($foundChars/6 chars Bajaj)")
                recordDebug(if (foundChars >= 5) "Painel reconhecido e pronto" else "Serviços básicos carregados")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                val requested = gatt.requestMtu(256)
                if (requested) {
                    updateStatus("Preparando painel...")
                    mainHandler.postDelayed(mtuFallbackRunnable, 1500)
                } else {
                    val displayName = preferences.getDisplayName(gatt.device?.address ?: currentDeviceAddress, currentDeviceName)
                    isMtuReady = true
                    isConnectingState = false
                    isPassiveWaitingState = false
                    maintenanceManager?.setBleConnected(true)
                    updateStatus("Conectado a $displayName")
                    startPeriodicLoops()
                    BajajForegroundService.updateStatus(
                        context,
                        "Conectado a $displayName",
                        keepWakeLock = true
                    )
                    synchronized(gattQueue) {
                        drainGattQueueLocked()
                    }
                }
            } else {
                Log.w(TAG, "Falha discoverServices status=$status")
                recordDebug("Falha ao carregar serviços do painel")
                disconnect(userInitiated = false)
                scheduleReconnectIfNeeded()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.removeCallbacks(mtuFallbackRunnable)
            val displayName = preferences.getDisplayName(gatt.device?.address ?: currentDeviceAddress, currentDeviceName)
            isMtuReady = true
            isConnectingState = false
            isPassiveWaitingState = false
            reconnectAttempt = 0
            maintenanceManager?.setBleConnected(true)
            Log.d(TAG, "MTU ativo: $mtu (status=$status)")
            recordDebug("Comunicação ativa")
            updateStatus("Conectado a $displayName")
            BajajForegroundService.updateStatus(
                context,
                "Conectado a $displayName",
                keepWakeLock = true
            )
            startPeriodicLoops()
            synchronized(gattQueue) {
                drainGattQueueLocked()
            }
            sendPhoneStatus()
            if (isNavigating) {
                sendNavigationPacket(
                    lastDistanceMeters,
                    lastManeuverCode,
                    lastNavText,
                    lastTotalDistanceMeters,
                    lastRoundaboutExit,
                    lastEtaSeconds,
                    lastEtaHours,
                    lastEtaMinutes,
                    lastIsAm
                )
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            mainHandler.removeCallbacks(gattOpTimeoutRunnable)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                recordDebug("Falha ao enviar para o painel")
                Log.w(TAG, "onCharacteristicWrite uuid=${characteristic.uuid} status=$status")
            } else if (characteristic.uuid == CHAR_ALERTS || characteristic.uuid == CHAR_CALLS) {
                Log.d(TAG, "Enviado com sucesso (${characteristic.uuid})")
                recordDebug("Aviso enviado ao painel")
            }
            synchronized(gattQueue) {
                isGattBusy = false
                drainGattQueueLocked()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: ByteArray(0)
            handleCharacteristicRead(characteristic.uuid, bytes, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(characteristic.uuid, value, status)
        }
    }

    private fun handleCharacteristicRead(uuid: UUID, value: ByteArray, status: Int) {
        mainHandler.removeCallbacks(gattOpTimeoutRunnable)
        if (status == BluetoothGatt.GATT_SUCCESS && uuid == CHAR_CONTROLS) {
            val ctrl = BajajProtocol.parseControlsPacket(value)
            if (ctrl != null) {
                processControlsPacket(ctrl)
            }
        }
        synchronized(gattQueue) {
            isGattBusy = false
            drainGattQueueLocked()
        }
    }

    private fun processControlsPacket(ctrl: BajajProtocol.ClusterControlsPacket) {
        if (ctrl.missedCallGet > 0 || ctrl.alertGet > 0) {
            Log.d(TAG, "Painel ACK controls: missed=${ctrl.missedCallGet} alert=${ctrl.alertGet}")
            recordDebug("Painel confirmou recebimento")
        }

        // Nas 2 primeiras leituras grava baseline (com campos de chamada zerados)
        if (controlsPollCount <= 1L) {
            lastCtrlVolume = ctrl.volumeToSet
            lastCtrlCallAccept = 0
            lastCtrlCallReject = 0
            lastCtrlCallRejectWithSms = 0
            lastCtrlPagePlaylist = ctrl.pagePlaylist
            lastCtrlTakeMeHome = ctrl.takeMeHome
            lastCtrlResumeSong = ctrl.resumeSong
            lastCtrlPauseSong = ctrl.pauseSong
            lastCtrlSkipToNext = ctrl.skipToNext
            lastCtrlSkipToPrev = ctrl.skipToPrev
            lastCtrlStopSong = ctrl.stopSong
            lastCtrlLaunchMediaPlayer = ctrl.launchMediaPlayer
            controlsPollCount++
            return
        }
        controlsPollCount++

        if (!preferences.isClusterControlsEnabled()) {
            lastCtrlVolume = ctrl.volumeToSet
            lastCtrlCallAccept = ctrl.callAccept
            lastCtrlCallReject = ctrl.callReject
            lastCtrlCallRejectWithSms = ctrl.callRejectWithSms
            lastCtrlPagePlaylist = ctrl.pagePlaylist
            lastCtrlTakeMeHome = ctrl.takeMeHome
            lastCtrlResumeSong = ctrl.resumeSong
            lastCtrlPauseSong = ctrl.pauseSong
            lastCtrlSkipToNext = ctrl.skipToNext
            lastCtrlSkipToPrev = ctrl.skipToPrev
            lastCtrlStopSong = ctrl.stopSong
            lastCtrlLaunchMediaPlayer = ctrl.launchMediaPlayer
            return
        }

        var actionRecorded: String? = null

        if (ctrl.volumeToSet != lastCtrlVolume) {
            lastCtrlVolume = ctrl.volumeToSet
            if (ctrl.volumeToSet != 0) {
                applyClusterVolume(ctrl.volumeToSet)
                actionRecorded = "Volume ajustado para ${ctrl.volumeToSet}/10"
            }
        }

        if (ctrl.callAccept != lastCtrlCallAccept) {
            lastCtrlCallAccept = ctrl.callAccept
            if (ctrl.callAccept != 0) {
                acceptIncomingCall()
                actionRecorded = "Atender chamada (guidão)"
            }
        }

        if (ctrl.callReject != lastCtrlCallReject) {
            lastCtrlCallReject = ctrl.callReject
            if (ctrl.callReject != 0) {
                rejectCurrentCall(sendSms = preferences.isAutoSmsOnRejectEnabled())
                actionRecorded = "Recusar chamada (guidão)"
            }
        }

        if (ctrl.callRejectWithSms != lastCtrlCallRejectWithSms) {
            lastCtrlCallRejectWithSms = ctrl.callRejectWithSms
            if (ctrl.callRejectWithSms != 0) {
                rejectCurrentCall(sendSms = true)
                actionRecorded = "Recusar + SMS (guidão)"
            }
        }

        if (ctrl.pagePlaylist != lastCtrlPagePlaylist) {
            lastCtrlPagePlaylist = ctrl.pagePlaylist
            actionRecorded = "Playlist/página (${ctrl.pagePlaylist})"
        }

        if (ctrl.takeMeHome != lastCtrlTakeMeHome) {
            lastCtrlTakeMeHome = ctrl.takeMeHome
            launchTakeMeHomeNavigation()
            actionRecorded = "Ir para casa (Maps)"
        }

        if (ctrl.resumeSong != lastCtrlResumeSong) {
            lastCtrlResumeSong = ctrl.resumeSong
            MapsNotificationListener.playMedia()
            actionRecorded = "Reproduzir mídia"
        }

        if (ctrl.pauseSong != lastCtrlPauseSong) {
            lastCtrlPauseSong = ctrl.pauseSong
            MapsNotificationListener.pauseMedia()
            actionRecorded = "Pausar mídia"
        }

        if (ctrl.skipToNext != lastCtrlSkipToNext) {
            lastCtrlSkipToNext = ctrl.skipToNext
            MapsNotificationListener.skipToNext()
            actionRecorded = "Próxima faixa"
        }

        if (ctrl.skipToPrev != lastCtrlSkipToPrev) {
            lastCtrlSkipToPrev = ctrl.skipToPrev
            MapsNotificationListener.skipToPreviousTwice()
            actionRecorded = "Faixa anterior"
        }

        if (ctrl.stopSong != lastCtrlStopSong) {
            lastCtrlStopSong = ctrl.stopSong
            MapsNotificationListener.stopMedia()
            actionRecorded = "Parar mídia"
        }

        if (ctrl.launchMediaPlayer != lastCtrlLaunchMediaPlayer) {
            lastCtrlLaunchMediaPlayer = ctrl.launchMediaPlayer
            MapsNotificationListener.launchMediaPlayer(context, preferences.getPreferredMediaPackage())
            actionRecorded = "Abrir player de música"
        }

        if (actionRecorded != null) {
            lastControlDebugSummary = actionRecorded
            Log.d(TAG, "0A10 controle executado: $actionRecorded")
            mainHandler.post { onDiagnosticsUpdated?.invoke() }
        }
    }

    private fun applyClusterVolume(vol0To10: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val stream = if (isInCallAudioContext()) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            val targetIndex = ((vol0To10 * maxVol) / 10.0).roundToInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(stream, targetIndex, AudioManager.FLAG_SHOW_UI)
            updateVolumeLevel()
            sendPhoneStatus()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao ajustar volume pelo painel: ${e.message}")
        }
    }

    private fun acceptIncomingCall() {
        try {
            if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                recordDebug("Libere a permissão de chamadas para atender pelo guidão")
                return
            }
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao atender chamada: ${e.message}")
        }
    }

    private fun rejectCurrentCall(sendSms: Boolean) {
        val numberToReply = lastIncomingPhoneNumber

        // Recusa chamada VoIP (WhatsApp, etc.) via ação da notificação
        val declinedVoip = MapsNotificationListener.declineActiveNotificationCall()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    @Suppress("DEPRECATION")
                    telecom?.endCall()
                } else if (!declinedVoip) {
                    recordDebug("Libere a permissão de chamadas para recusar pelo guidão")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao encerrar chamada: ${e.message}")
        }

        if (sendSms && numberToReply.isNotBlank()) {
            sendQuickReplySms(numberToReply)
        }
    }

    private fun sendQuickReplySms(phoneNumber: String) {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            recordDebug("Libere a permissão de SMS para enviar resposta rápida")
            return
        }
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return
            val body = preferences.getQuickReplySmsText()
            smsManager.sendTextMessage(phoneNumber, null, body, null, null)
            lastAlertDispatchSummary = "SMS resposta enviado para $phoneNumber"
            recordDebug("SMS de recusa enviado")
            mainHandler.post { onDiagnosticsUpdated?.invoke() }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao enviar SMS de recusa: ${e.message}")
        }
    }

    private fun launchTakeMeHomeNavigation() {
        val destQuery = preferences.getHomeNavigationQuery()
        val mapsUri = if (destQuery.isNotBlank()) {
            Uri.parse("google.navigation:q=${Uri.encode(destQuery)}&mode=l")
        } else {
            Uri.parse("geo:0,0?q=casa")
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }

        try {
            val fallback = Intent(Intent.ACTION_VIEW, mapsUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao iniciar rota TakeMeHome: ${e.message}")
        }
    }

    fun sendPhoneStatus(
        battery: Int = currentBattery,
        signal: Int = currentSignal,
        callState: Int = currentCallState,
        caller: String = currentCallerName
    ) {
        currentBattery = battery.coerceIn(0, 100)
        currentSignal = signal.coerceIn(0, 4)
        currentCallState = callState
        currentCallerName = caller
        heartbeatCounter = (heartbeatCounter + 1) % 256

        val packet = BajajProtocol.createPhoneStatusPacket(
            batteryPercent = currentBattery,
            signalLevel = currentSignal,
            heartbeatCounter = heartbeatCounter,
            isHeadsetConnected = isHeadsetConnected,
            volumeLevel = currentVolumeLevel,
            callState = callState,
            callerName = caller
        )
        enqueueWrite(CHAR_STATUS, packet, replacePending = true)
        notifyDataRefreshedIfChanged()
    }

    fun onMapsNavigationAnchor(parsed: ParsedMapsNavigation, provider: NavProvider = NavProvider.GOOGLE_MAPS) {
        hybridNavigator.onMapsAnchorUpdate(parsed, provider)
    }

    fun onMapsNotificationCleared() {
        hybridNavigator.onMapsNotificationCleared()
    }

    private fun isHybridSourceLabel(sourceLabel: String): Boolean {
        return sourceLabel == "Google Maps" || sourceLabel == "Maps + GPS"
    }

    private fun applyHybridNavigationOutput(out: HybridNavOutput) {
        isNavigating = out.isActive
        isNavArrived = out.isArrived
        lastDistanceMeters = out.distanceMeters
        lastTotalDistanceMeters = out.totalDistanceMeters
        lastRoundaboutExit = out.roundaboutExit
        lastManeuverCode = out.maneuverCode
        lastNavText = out.streetText.ifBlank { out.sourceLabel }
        lastNavSource = out.sourceLabel
        lastEtaSeconds = out.etaSeconds
        lastEtaHours = out.etaHours
        lastEtaMinutes = out.etaMinutes
        lastIsAm = out.isAm

        val gpsSuffix = if (out.gpsActive) " • GPS ativo" else ""
        lastMapsCaptureSummary = if (out.isArrived) {
            "${out.sourceLabel}: Chegou ao destino"
        } else {
            val distStr = if (out.distanceMeters >= 1000) {
                String.format(Locale.US, "%.1f km", out.distanceMeters / 1000.0)
            } else {
                "${out.distanceMeters} m"
            }
            "${out.sourceLabel}: $distStr • $lastNavText$gpsSuffix"
        }

        val now = System.currentTimeMillis()
        val shouldWriteBle = out.isArrived ||
            out.maneuverCode != lastNavBleSentCode ||
            (now - lastNavBleWriteMs >= 650L && out.distanceMeters != lastNavBleSentDist)

        if (shouldWriteBle) {
            lastNavBleWriteMs = now
            lastNavBleSentCode = out.maneuverCode
            lastNavBleSentDist = out.distanceMeters
            sendNavigationPacket(
                distanceMeters = out.distanceMeters,
                maneuverCode = out.maneuverCode,
                text = lastNavText,
                totalDistanceMeters = out.totalDistanceMeters,
                roundaboutExit = out.roundaboutExit,
                etaSeconds = out.etaSeconds,
                etaHours = out.etaHours,
                etaMinutes = out.etaMinutes,
                isAm = out.isAm
            )
        }

        mainHandler.post {
            onNavigationUpdated?.invoke(getNavigationSnapshot())
            onDiagnosticsUpdated?.invoke()
        }
    }

    fun sendNavigation(
        distanceMeters: Int,
        maneuverCode: Byte,
        text: String,
        totalDistanceMeters: Int = distanceMeters,
        roundaboutExit: Int = 0,
        etaSeconds: Int = -1,
        etaHours: Int = -1,
        etaMinutes: Int = -1,
        isAm: Boolean? = null,
        sourceLabel: String = "Google Maps"
    ) {
        if (!isHybridSourceLabel(sourceLabel)) {
            hybridNavigator.reset()
        }
        isNavigating = true
        isNavArrived = false
        lastDistanceMeters = distanceMeters
        lastTotalDistanceMeters = maxOf(distanceMeters, totalDistanceMeters)
        lastRoundaboutExit = roundaboutExit
        lastManeuverCode = maneuverCode
        lastNavText = text.ifBlank { sourceLabel }
        lastNavSource = sourceLabel
        lastEtaSeconds = etaSeconds
        lastEtaHours = etaHours
        lastEtaMinutes = etaMinutes
        lastIsAm = isAm

        if (isHybridSourceLabel(sourceLabel)) {
            val distStr = if (distanceMeters >= 1000) {
                String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
            } else {
                "$distanceMeters m"
            }
            lastMapsCaptureSummary = "$sourceLabel: $distStr • $lastNavText"
        }

        lastNavBleWriteMs = System.currentTimeMillis()
        lastNavBleSentCode = maneuverCode
        lastNavBleSentDist = distanceMeters

        sendNavigationPacket(
            distanceMeters = distanceMeters,
            maneuverCode = maneuverCode,
            text = lastNavText,
            totalDistanceMeters = lastTotalDistanceMeters,
            roundaboutExit = roundaboutExit,
            etaSeconds = etaSeconds,
            etaHours = etaHours,
            etaMinutes = etaMinutes,
            isAm = isAm
        )
        mainHandler.post {
            onNavigationUpdated?.invoke(getNavigationSnapshot())
            onDiagnosticsUpdated?.invoke()
        }
    }

    fun stopNavigation() {
        hybridNavigator.reset()
        isNavigating = false
        isNavArrived = false
        lastDistanceMeters = 0
        lastTotalDistanceMeters = 0
        lastRoundaboutExit = 0
        lastManeuverCode = BajajProtocol.ARROW_STRAIGHT_SOLID
        lastNavText = "Aguardando rota no Google Maps"
        lastNavSource = "Inativo"
        lastEtaSeconds = -1
        lastEtaHours = -1
        lastEtaMinutes = -1
        lastIsAm = null
        lastNavBleSentCode = 0
        lastNavBleSentDist = -1
        enqueueWrite(CHAR_TBT, BajajProtocol.createTbtEndPacket(), replacePending = true)
        mainHandler.post {
            onNavigationUpdated?.invoke(getNavigationSnapshot())
            onDiagnosticsUpdated?.invoke()
        }
    }

    private fun sendNavigationPacket(
        distanceMeters: Int,
        maneuverCode: Byte,
        text: String,
        totalDistanceMeters: Int,
        roundaboutExit: Int,
        etaSeconds: Int,
        etaHours: Int,
        etaMinutes: Int,
        isAm: Boolean?
    ) {
        val packet = BajajProtocol.createTbtPacket(
            distanceMeters = distanceMeters,
            maneuverCode = maneuverCode,
            text = text,
            totalDistanceMeters = totalDistanceMeters,
            etaSeconds = etaSeconds,
            isAm = isAm,
            etaMinutes = etaMinutes,
            etaHours = etaHours,
            roundaboutExit = roundaboutExit
        )
        enqueueWrite(CHAR_TBT, packet, replacePending = true)
    }

    fun sendMusic(
        title: String,
        artist: String = "",
        album: String = "",
        durationSec: Int = 0,
        positionSec: Int = 0,
        isPlaying: Boolean = true,
        playStatusOverride: Byte? = null
    ) {
        currentSongTitle = cleanForClusterText(title)
        currentArtist = cleanForClusterText(artist)
        currentAlbum = cleanForClusterText(album)
        val resolvedStatus = playStatusOverride
            ?: if (currentSongTitle.isBlank()) BajajProtocol.PLAY_STATUS_NONE
            else if (isPlaying) BajajProtocol.PLAY_STATUS_PLAY
            else BajajProtocol.PLAY_STATUS_PAUSED
        currentMusicPlayStatus = resolvedStatus
        isMusicPlaying = resolvedStatus == BajajProtocol.PLAY_STATUS_PLAY

        val packet = BajajProtocol.createMusicPacket(
            title = currentSongTitle,
            artist = currentArtist,
            album = currentAlbum,
            durationSec = durationSec,
            positionSec = positionSec,
            isPlaying = isMusicPlaying,
            playStatusOverride = resolvedStatus
        )
        enqueueWrite(CHAR_MEDIA, packet, replacePending = true)
        mainHandler.post { onDiagnosticsUpdated?.invoke() }
    }

    fun sendMissedCall(callerName: String, callerNumber: String) {
        val cleanName = cleanForClusterText(callerName).ifBlank { "Alerta" }
        val cleanNum = cleanForClusterText(callerNumber).ifBlank { "App" }
        val packet = BajajProtocol.createMissedCallPacket(cleanName, cleanNum)
        enqueueWrite(CHAR_CALLS, packet, replacePending = false)
        lastAlertDispatchSummary = "Chamada perdida: \"$cleanName\""
        Log.d(TAG, "Enfileirado CHAR_CALLS: $cleanName")
        recordDebug("Enviando chamada perdida...")
        mainHandler.post { onDiagnosticsUpdated?.invoke() }
    }

    fun sendAlertNotification(alertType: Byte, message: String) {
        val cleanMsg = cleanForClusterText(message).ifBlank { "Nova mensagem" }
        val packet = BajajProtocol.createAlertPacket(alertType, cleanMsg)
        enqueueWrite(CHAR_ALERTS, packet, replacePending = false)
        val kind = if (alertType == BajajProtocol.ALERT_SMS) "SMS" else "Mensagens"
        lastAlertDispatchSummary = "Piscando ($kind): \"$cleanMsg\""
        Log.d(TAG, "Enfileirado CHAR_ALERTS ($kind): $cleanMsg")
        recordDebug("Enviando aviso piscante...")
        mainHandler.post { onDiagnosticsUpdated?.invoke() }
    }

    fun clearPanelAlerts() {
        enqueueWrite(CHAR_ALERTS, ByteArray(40), replacePending = true)
        enqueueWrite(CHAR_CALLS, ByteArray(74), replacePending = true)
        lastAlertDispatchSummary = "Alertas limpos no painel"
        Log.d(TAG, "Limpeza enviada para CHAR_ALERTS e CHAR_CALLS")
        recordDebug("Limpeza enviada ao painel")
        mainHandler.post { onDiagnosticsUpdated?.invoke() }
    }

    fun sendConfiguredAppAlert(
        appLabel: String,
        title: String,
        text: String,
        overrideMode: PanelAlertMode? = null
    ) {
        val mode = overrideMode ?: preferences.getPanelAlertMode()
        val payload = formatNotificationPayload(appLabel, title, text)
        when (mode) {
            PanelAlertMode.BLINK_WAPP -> sendAlertNotification(BajajProtocol.ALERT_WHATSAPP, payload)
            PanelAlertMode.BLINK_SMS -> sendAlertNotification(BajajProtocol.ALERT_SMS, payload)
            PanelAlertMode.SOLID_ALERT -> sendMissedCall(payload, appLabel.ifBlank { "Alerta" })
        }
    }

    private fun formatNotificationPayload(appLabel: String, title: String, text: String): String {
        val cleanTitle = cleanForClusterText(title)
        val cleanText = cleanForClusterText(text)
        val cleanApp = cleanForClusterText(appLabel)
        val raw = when {
            cleanTitle.isNotEmpty() && !cleanTitle.equals(cleanApp, ignoreCase = true) && cleanText.isNotEmpty() ->
                "$cleanTitle: $cleanText"
            cleanTitle.isNotEmpty() && !cleanTitle.equals(cleanApp, ignoreCase = true) ->
                cleanTitle
            cleanText.isNotEmpty() ->
                if (cleanApp.isNotEmpty()) "$cleanApp: $cleanText" else cleanText
            else ->
                cleanApp.ifBlank { "Mensagem" }
        }
        return String(BajajProtocol.truncateUtf8(raw, BajajProtocol.MAX_ALERT_BYTES), Charsets.UTF_8)
    }

    private fun cleanForClusterText(raw: String): String {
        return raw.replace("[\\p{So}\\p{Cn}\\p{Cf}]".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun enqueueWrite(uuid: UUID, data: ByteArray, replacePending: Boolean) {
        if (bluetoothGatt == null || !isMtuReady) return
        synchronized(gattQueue) {
            if (replacePending) {
                val it = gattQueue.iterator()
                while (it.hasNext()) {
                    val op = it.next()
                    if (op.type == GattOpType.WRITE && op.uuid == uuid) {
                        it.remove()
                    }
                }
            }
            if (gattQueue.size >= 24) {
                val removable = gattQueue.firstOrNull { it.type == GattOpType.READ || it.uuid == CHAR_STATUS }
                if (removable != null) {
                    gattQueue.remove(removable)
                } else {
                    gattQueue.pollFirst()
                }
            }
            val isHighPriority = uuid == CHAR_ALERTS || uuid == CHAR_CALLS || uuid == CHAR_TBT
            if (isHighPriority) {
                val it = gattQueue.iterator()
                while (it.hasNext()) {
                    if (it.next().type == GattOpType.READ) {
                        it.remove()
                    }
                }
            }
            gattQueue.addLast(GattOp(GattOpType.WRITE, uuid, data))
            if (!isGattBusy) {
                drainGattQueueLocked()
            }
        }
    }

    private fun enqueueRead(uuid: UUID) {
        if (bluetoothGatt == null || !isMtuReady) return
        synchronized(gattQueue) {
            val alreadyPending = gattQueue.any { it.type == GattOpType.READ && it.uuid == uuid }
            if (alreadyPending || gattQueue.size >= 16) return
            gattQueue.addLast(GattOp(GattOpType.READ, uuid))
            if (!isGattBusy) {
                drainGattQueueLocked()
            }
        }
    }

    private fun drainGattQueueLocked() {
        val gatt = bluetoothGatt ?: run {
            gattQueue.clear()
            isGattBusy = false
            return
        }
        while (gattQueue.isNotEmpty()) {
            val op = gattQueue.pollFirst() ?: continue
            val char = findCharacteristic(gatt, op.uuid)
            if (char == null) {
                if (op.uuid == CHAR_ALERTS || op.uuid == CHAR_CALLS) {
                    Log.w(TAG, "Característica ${op.uuid} não encontrada no painel")
                    recordDebug("Painel ainda não suporta este recurso")
                }
                continue
            }

            val initiated = when (op.type) {
                GattOpType.READ -> gatt.readCharacteristic(char)
                GattOpType.WRITE -> writeCharacteristicCompat(gatt, char, op.data)
            }

            if (initiated) {
                isGattBusy = true
                gattOpCounter++
                mainHandler.removeCallbacks(gattOpTimeoutRunnable)
                mainHandler.postDelayed(gattOpTimeoutRunnable, 1200)
                return
            } else {
                Log.w(TAG, "Falha ao iniciar ${op.type} ${op.uuid}")
                recordDebug("Falha temporária ao comunicar com o painel")
            }
        }
        isGattBusy = false
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val res = gatt.writeCharacteristic(char, data, writeType)
            if (res != BluetoothStatusCodes.SUCCESS) {
                Log.w(TAG, "writeCharacteristic TIRAMISU uuid=${char.uuid} code=$res")
            }
            res == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            char.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    private fun findCharacteristic(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (service in gatt.services) {
            val char = service.getCharacteristic(uuid)
            if (char != null) return char
        }
        return null
    }

    private fun updateStatus(msg: String) {
        lastStatusMessage = msg
        mainHandler.post {
            onStatusChanged?.invoke(msg)
            onConnectionMetaChanged?.invoke()
        }
    }

    private fun recordDebug(msg: String) {
        lastBleDebugEvent = msg
        Log.d(TAG, msg)
        mainHandler.post { onDiagnosticsUpdated?.invoke() }
    }
}
