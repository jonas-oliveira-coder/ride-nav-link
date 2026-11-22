package com.custom.ridenavlink

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

class BajajForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var btReceiverRegistered = false
    private var isLocationModeActive = false
    private var lastStatusText = "Monitorando conexão e rotas em segundo plano"

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val app = BajajBridgeApp.instance ?: (applicationContext as? BajajBridgeApp) ?: return
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    app.bleManager.tryAutoConnectPinned()
                }
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    app.bleManager.disconnect(userInitiated = false)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "ridenav_link_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val ACTION_UPDATE_STATUS = "com.custom.ridenavlink.ACTION_UPDATE_STATUS"
        private const val ACTION_SET_LOCATION_MODE = "com.custom.ridenavlink.ACTION_SET_LOCATION_MODE"
        private const val EXTRA_STATUS_TEXT = "extra_status_text"
        private const val EXTRA_KEEP_WAKELOCK = "extra_keep_wakelock"
        private const val EXTRA_LOCATION_MODE = "extra_location_mode"

        @Volatile
        var isRunning: Boolean = false
            private set

        private fun canStartConnectedDeviceFgs(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val connectOk = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val scanOk = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            return connectOk || scanOk
        }

        fun startService(context: Context, statusText: String = "Monitorando conexão com o painel") {
            if (!canStartConnectedDeviceFgs(context)) return
            val intent = Intent(context, BajajForegroundService::class.java).apply {
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateStatus(context: Context, statusText: String, keepWakeLock: Boolean) {
            if (!canStartConnectedDeviceFgs(context)) return
            try {
                val intent = Intent(context, BajajForegroundService::class.java).apply {
                    action = ACTION_UPDATE_STATUS
                    putExtra(EXTRA_STATUS_TEXT, statusText)
                    putExtra(EXTRA_KEEP_WAKELOCK, keepWakeLock)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun updateLocationTrackingMode(context: Context, active: Boolean) {
            if (!isRunning && !active) return
            if (!canStartConnectedDeviceFgs(context)) return
            try {
                val intent = Intent(context, BajajForegroundService::class.java).apply {
                    action = ACTION_SET_LOCATION_MODE
                    putExtra(EXTRA_LOCATION_MODE, active)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BajajForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        val notification = buildForegroundNotification(lastStatusText)
        startForegroundCompat(notification)

        if (!btReceiverRegistered) {
            try {
                registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
                btReceiverRegistered = true
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        val action = intent?.action
        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
        val keepWakeLock = intent?.getBooleanExtra(EXTRA_KEEP_WAKELOCK, false) ?: false

        if (action == ACTION_SET_LOCATION_MODE) {
            val wantLoc = intent.getBooleanExtra(EXTRA_LOCATION_MODE, false)
            if (wantLoc != isLocationModeActive) {
                isLocationModeActive = wantLoc
                startForegroundCompat(buildForegroundNotification(lastStatusText))
            }
            return START_STICKY
        }

        if (intent != null && intent.hasExtra(EXTRA_KEEP_WAKELOCK)) {
            updateWakeLock(keepWakeLock)
        }

        if (!statusText.isNullOrBlank() && statusText != lastStatusText) {
            lastStatusText = statusText
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
        }

        if (action == ACTION_UPDATE_STATUS) {
            return START_STICKY
        }

        val app = BajajBridgeApp.instance ?: (applicationContext as? BajajBridgeApp)
        if (app != null) {
            app.maintenanceManager.refreshTrackingAndNotify()
            if (app.preferences.isAutoStartEnabled()) {
                MapsNotificationListener.requestRebindIfEnabled(this)
                if (!app.bleManager.isConnected() && !app.bleManager.isConnecting()) {
                    app.bleManager.tryAutoConnectPinned(preferPassiveAutoConnect = true)
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = 0
            if (canStartConnectedDeviceFgs(this)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            val hasLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (isLocationModeActive && hasLoc) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (types != 0) {
                startForeground(NOTIFICATION_ID, notification, types)
                return
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val app = BajajBridgeApp.instance ?: (applicationContext as? BajajBridgeApp) ?: return
        if (!app.preferences.isAutoStartEnabled()) return

        // Ensures the service stays alive or restarts if the user swipes away recent apps
        try {
            val restartIntent = Intent(applicationContext, BajajForegroundService::class.java)
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext,
                    1002,
                    restartIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
            } else {
                PendingIntent.getService(
                    applicationContext,
                    1002,
                    restartIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
            }
            val alarm = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarm?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500L, pi)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isRunning = false
        if (btReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothStateReceiver)
            } catch (_: Exception) {
            }
            btReceiverRegistered = false
        }
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateWakeLock(acquire: Boolean) {
        if (acquire) {
            try {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideNavLink::BleKeepAlive")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(4 * 60 * 60 * 1000L)
                }
            } catch (_: Exception) {
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.deleteNotificationChannel("dominar_service_channel")
            manager?.deleteNotificationChannel("bajaj_open_service_channel")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RideNav Link Conexão Ativa",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a conexão e o espelhamento com o painel da moto"
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("RideNav Link")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_brand_mark)
            .setColor(Color.parseColor("#0B57D0"))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
