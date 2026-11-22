package com.custom.bajajbridge

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

class MapsNotificationListener : NotificationListenerService() {

    private var isMapsRouteActive: Boolean = false
    private var lastMapsTurnDistanceMeters: Int? = null
    private var lastMapsTotalDistanceMeters: Int? = null
    private var lastMapsSolidManeuver: Byte? = null
    private var lastMapsRoundaboutExit: Int = 0
    private var prefsInstance: AppPreferences? = null
    private fun getPrefs(): AppPreferences {
        val p = prefsInstance
        if (p != null) return p
        val created = AppPreferences(this)
        prefsInstance = created
        return created
    }

    private val activeAlertSignatures = LinkedHashMap<String, Pair<Long, String>>()
    private val recentAlertTimestamps = LinkedHashMap<String, Long>()
    private val activeCallKeys = LinkedHashSet<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null
    private var sessionsListenerRegistered = false
    private var mediaTickerRunning = false
    private var lastSentMusicSignature = ""
    private var lastSentMusicTimeMs = 0L

    private val mediaTickerRunnable = object : Runnable {
        override fun run() {
            if (!mediaTickerRunning) return
            val ctrl = activeMediaController
            if (ctrl != null && isControllerPlaying(ctrl.playbackState)) {
                dispatchCurrentMediaState(ctrl, force = false)
                mainHandler.postDelayed(this, 1000L)
            } else {
                mediaTickerRunning = false
            }
        }
    }

    private val activeSessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindBestMediaController(controllers)
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val ctrl = activeMediaController ?: return
            dispatchCurrentMediaState(ctrl, force = true)
            updateMediaTickerState(ctrl.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val ctrl = activeMediaController ?: return
            dispatchCurrentMediaState(ctrl, force = true)
            updateMediaTickerState(state)
        }

        override fun onSessionDestroyed() {
            unbindMediaController()
            refreshActiveMediaSessions()
        }
    }

    companion object {
        private const val TAG = "RideNavListener"
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"

        private val CALL_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )

        @Volatile
        private var activeInstance: MapsNotificationListener? = null

        @Volatile
        private var lastCallDeclineIntent: PendingIntent? = null

        fun declineActiveNotificationCall(): Boolean {
            val intent = lastCallDeclineIntent ?: return false
            lastCallDeclineIntent = null
            return try {
                intent.send()
                Log.d(TAG, "Chamada recusada via ação da notificação")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao recusar chamada via notificação: ${e.message}")
                false
            }
        }

        fun isEnabledInSettings(context: Context): Boolean {
            return try {
                val flat = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: return false
                val component = ComponentName(context, MapsNotificationListener::class.java)
                flat.contains(component.flattenToString()) || flat.contains(component.flattenToShortString())
            } catch (_: Exception) {
                false
            }
        }

        fun requestRebindIfEnabled(context: Context) {
            if (!isEnabledInSettings(context)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(ComponentName(context, MapsNotificationListener::class.java))
                } catch (_: Exception) {
                }
            }
        }

        fun playMedia(): Boolean {
            val inst = activeInstance ?: return false
            return inst.performTransportAction { it.play() }
        }

        fun pauseMedia(): Boolean {
            val inst = activeInstance ?: return false
            return inst.performTransportAction { it.pause() }
        }

        fun skipToNext(): Boolean {
            val inst = activeInstance ?: return false
            return inst.performTransportAction { it.skipToNext() }
        }

        // Chama skipToPrevious() duas vezes para reiniciar a faixa ou voltar à anterior conforme esperado pelo controle do guidão
        fun skipToPreviousTwice(): Boolean {
            val inst = activeInstance ?: return false
            return inst.performTransportAction { controls ->
                controls.skipToPrevious()
                controls.skipToPrevious()
            }
        }

        fun stopMedia(): Boolean {
            val inst = activeInstance ?: return false
            return inst.performTransportAction { it.stop() }
        }

        fun getActiveMediaPackage(): String? {
            return activeInstance?.activeMediaController?.packageName
        }

        fun launchMediaPlayer(context: Context, preferredPkg: String = ""): Boolean {
            val pm = context.packageManager
            val candidates = linkedSetOf<String>()
            activeInstance?.activeMediaController?.packageName?.takeIf { it.isNotBlank() }?.let {
                candidates.add(it)
            }
            if (preferredPkg.isNotBlank()) {
                candidates.add(preferredPkg)
            }
            candidates.addAll(
                listOf(
                    "com.spotify.music",
                    "com.google.android.apps.youtube.music",
                    "deezer.android.app",
                    "com.amazon.mp3",
                    "com.sec.android.app.music",
                    "com.apple.android.music",
                    "com.soundcloud.android",
                    "com.google.android.youtube"
                )
            )

            for (pkg in candidates) {
                try {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return true
                    }
                } catch (_: Exception) {
                }
            }

            return try {
                val fallback = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_MUSIC)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                try {
                    val legacy = Intent("android.intent.action.MUSIC_PLAYER").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(legacy)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    private fun appOrNull(): BajajBridgeApp? = BajajBridgeApp.instance ?: (applicationContext as? BajajBridgeApp)

    private fun performTransportAction(action: (MediaController.TransportControls) -> Unit): Boolean {
        val ctrl = activeMediaController ?: run {
            refreshActiveMediaSessions()
            activeMediaController
        } ?: return false
        return try {
            action(ctrl.transportControls)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao controlar MediaController: ${e.message}")
            false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        val app = appOrNull()
        app?.bleManager?.setNotificationListenerConnected(true)
        if (app != null && app.preferences.isAutoStartEnabled() && !BajajForegroundService.isRunning) {
            try {
                BajajForegroundService.startService(applicationContext)
            } catch (_: Exception) {
            }
        }

        setupMediaSessionMonitoring()

        Log.d(TAG, "NotificationListener conectado; verificando notificações ativas")
        try {
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName == MAPS_PACKAGE ||
                    isCallCandidate(sbn.packageName, sbn.notification)
                ) {
                    onNotificationPosted(sbn)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        if (activeInstance === this) {
            activeInstance = null
        }
        teardownMediaSessionMonitoring()
        activeCallKeys.clear()
        resetMapsSession()
        appOrNull()?.bleManager?.setNotificationListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        teardownMediaSessionMonitoring()
        super.onDestroy()
    }

    private fun setupMediaSessionMonitoring() {
        try {
            val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            mediaSessionManager = mgr
            val component = ComponentName(this, MapsNotificationListener::class.java)
            if (!sessionsListenerRegistered) {
                mgr.addOnActiveSessionsChangedListener(activeSessionsChangedListener, component, mainHandler)
                sessionsListenerRegistered = true
            }
            val current = mgr.getActiveSessions(component)
            bindBestMediaController(current)
        } catch (e: Exception) {
            Log.w(TAG, "MediaSessionManager indisponível: ${e.message}")
        }
    }

    private fun refreshActiveMediaSessions() {
        try {
            val mgr = mediaSessionManager ?: (getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager)
                ?: return
            val component = ComponentName(this, MapsNotificationListener::class.java)
            bindBestMediaController(mgr.getActiveSessions(component))
        } catch (_: Exception) {
        }
    }

    private fun teardownMediaSessionMonitoring() {
        stopMediaTicker()
        unbindMediaController()
        if (sessionsListenerRegistered) {
            try {
                mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
            } catch (_: Exception) {
            }
            sessionsListenerRegistered = false
        }
    }

    private fun bindBestMediaController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            unbindMediaController()
            appOrNull()?.bleManager?.sendMusic(
                title = "",
                artist = "",
                album = "",
                durationSec = 0,
                positionSec = 0,
                isPlaying = false,
                playStatusOverride = BajajProtocol.PLAY_STATUS_NONE
            )
            return
        }

        val chosen = controllers.firstOrNull { isControllerPlaying(it.playbackState) }
            ?: controllers.firstOrNull {
                val title = it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                !title.isNullOrBlank()
            }
            ?: controllers.first()

        if (activeMediaController?.sessionToken != chosen.sessionToken) {
            unbindMediaController()
            activeMediaController = chosen
            try {
                chosen.registerCallback(mediaControllerCallback, mainHandler)
            } catch (_: Exception) {
            }
        }

        dispatchCurrentMediaState(chosen, force = true)
        updateMediaTickerState(chosen.playbackState)
    }

    private fun unbindMediaController() {
        stopMediaTicker()
        val prev = activeMediaController ?: return
        activeMediaController = null
        try {
            prev.unregisterCallback(mediaControllerCallback)
        } catch (_: Exception) {
        }
    }

    private fun updateMediaTickerState(state: PlaybackState?) {
        if (isControllerPlaying(state)) {
            if (!mediaTickerRunning) {
                mediaTickerRunning = true
                mainHandler.removeCallbacks(mediaTickerRunnable)
                mainHandler.postDelayed(mediaTickerRunnable, 1000L)
            }
        } else {
            stopMediaTicker()
        }
    }

    private fun stopMediaTicker() {
        mediaTickerRunning = false
        mainHandler.removeCallbacks(mediaTickerRunnable)
    }

    private fun isControllerPlaying(state: PlaybackState?): Boolean {
        val code = state?.state ?: return false
        return code == PlaybackState.STATE_PLAYING ||
            code == PlaybackState.STATE_FAST_FORWARDING ||
            code == PlaybackState.STATE_REWINDING
    }

    private fun dispatchCurrentMediaState(controller: MediaController, force: Boolean) {
        val bleManager = appOrNull()?.bleManager ?: return
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val title = (
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: ""
            ).trim()
        val artist = (
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: ""
            ).trim()
        val album = (
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: ""
            ).trim()

        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        val durationSec = (durationMs / 1000L).toInt().coerceIn(0, 65535)

        val stateCode = playbackState?.state ?: PlaybackState.STATE_NONE
        val isStatePlaying = isControllerPlaying(playbackState)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isAudioActive = audioManager?.isMusicActive ?: isStatePlaying

        var positionMs = playbackState?.position?.coerceAtLeast(0L) ?: 0L
        if (isStatePlaying && playbackState != null && playbackState.lastPositionUpdateTime > 0L) {
            val delta = SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime
            if (delta > 0L) {
                val speed = if (playbackState.playbackSpeed > 0f) playbackState.playbackSpeed else 1.0f
                positionMs += (delta * speed).toLong()
            }
        }
        if (durationMs > 0L && positionMs > durationMs) {
            positionMs = durationMs
        }
        val positionSec = (positionMs / 1000L).toInt().coerceIn(0, 65535)

        val playStatus: Byte = when {
            title.isEmpty() || stateCode == PlaybackState.STATE_NONE || stateCode == PlaybackState.STATE_STOPPED ->
                BajajProtocol.PLAY_STATUS_NONE
            isStatePlaying && isAudioActive ->
                BajajProtocol.PLAY_STATUS_PLAY
            else ->
                BajajProtocol.PLAY_STATUS_PAUSED
        }

        val now = System.currentTimeMillis()
        val sig = "$title|$artist|$album|$playStatus|$durationSec"
        if (!force && sig == lastSentMusicSignature && (now - lastSentMusicTimeMs) < 950L) {
            return
        }
        lastSentMusicSignature = sig
        lastSentMusicTimeMs = now

        bleManager.sendMusic(
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            positionSec = positionSec,
            isPlaying = playStatus == BajajProtocol.PLAY_STATUS_PLAY,
            playStatusOverride = playStatus
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        val extras = notification.extras ?: return
        val app = appOrNull() ?: return
        val bleManager = app.bleManager
        val preferences = app.preferences

        bleManager.setNotificationListenerConnected(true)

        // Ignorar notificações próprias do RideNav Link
        if (pkg == packageName) return

        // Ignorar notificações de resumo de grupo (evita duplicatas e falsos positivos)
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        // 1. Interceptar Chamadas em tempo real (Telefone / Samsung InCallUI / Google Dialer / WhatsApp / W4B)
        if (isCallCandidate(pkg, notification)) {
            val callOutcome = extractCallStateAndCaller(notification, extras, pkg, bleManager)
            if (callOutcome != null) {
                val (callState, callerName) = callOutcome
                sbn.key?.let { activeCallKeys.add(it) }
                val sourceTag = when (pkg) {
                    "com.whatsapp" -> "WhatsApp"
                    "com.whatsapp.w4b" -> "WhatsApp Business"
                    else -> "Telefone"
                }
                Log.d(TAG, "Ligação em tempo real ($sourceTag, state=$callState): $callerName")
                bleManager.updateNotificationCallState(
                    active = true,
                    callState = callState,
                    callerCandidate = callerName,
                    sourceLabel = sourceTag
                )
                return
            }
        }

        // 2. Interceptar Navegação (Google Maps)
        if (pkg == MAPS_PACKAGE) {
            handleMapsNavigationNotification(notification, extras, bleManager)
            return
        }

        // 3. Fallback de Música via notificação apenas se nenhum MediaController ativo estiver vinculado
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (isOngoing && activeMediaController == null &&
            (pkg == "com.spotify.music" || pkg == "com.google.android.apps.youtube.music" || pkg.contains("music") || pkg.contains("audio"))
        ) {
            val trackTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val artistName = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: "Música"
            if (trackTitle.isNotEmpty()) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val playing = audioManager?.isMusicActive ?: true
                bleManager.sendMusic(
                    title = trackTitle,
                    artist = artistName,
                    album = "",
                    durationSec = 0,
                    positionSec = 0,
                    isPlaying = playing,
                    playStatusOverride = if (playing) BajajProtocol.PLAY_STATUS_PLAY else BajajProtocol.PLAY_STATUS_PAUSED
                )
            }
            return
        }

        // 4. Alertas configuráveis por aplicativo selecionado (CHAR_ALERTS piscante)
        if (isOngoing) {
            return
        }

        val cat = notification.category
        if (cat == Notification.CATEGORY_PROGRESS ||
            cat == Notification.CATEGORY_SERVICE ||
            cat == Notification.CATEGORY_SYSTEM ||
            cat == Notification.CATEGORY_STATUS ||
            cat == Notification.CATEGORY_TRANSPORT
        ) {
            return
        }

        if (!preferences.isPackageSelectedForAlerts(pkg)) {
            return
        }

        val title = (
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence("android.title")?.toString()
                ?: ""
            ).trim()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence("android.text")?.toString()
                ?: ""
            ).trim()

        if (!isValidMessageAlert(title, text)) {
            return
        }

        // Ignora re-posts antigos (ex: histórico ou atualização silenciosa ao abrir o serviço)
        val postTime = sbn.postTime
        val now = System.currentTimeMillis()
        if (postTime > 0L && (now - postTime) > 60_000L) {
            return
        }

        // Evita re-piscar o painel em atualizações silenciosas (FLAG_ONLY_ALERT_ONCE)
        val sbnKey = sbn.key ?: "$pkg:${sbn.id}"
        val contentSig = "$pkg|$title|$text"
        val prev = activeAlertSignatures[sbnKey]
        val isOnlyAlertOnce = (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        if (prev != null) {
            val (prevPostTime, prevSig) = prev
            if (prevSig == contentSig || (isOnlyAlertOnce && postTime <= prevPostTime)) {
                return
            }
        }
        activeAlertSignatures[sbnKey] = postTime to contentSig
        if (activeAlertSignatures.size > 64) {
            val oldest = activeAlertSignatures.keys.firstOrNull()
            if (oldest != null) activeAlertSignatures.remove(oldest)
        }

        val appLabel = resolveAppLabel(pkg)
        dispatchAlertDeduped(contentSig, appLabel, title, text, bleManager)
    }

    private fun isCallCandidate(pkg: String?, notification: Notification?): Boolean {
        if (pkg.isNullOrBlank() || notification == null) return false
        if (notification.category == Notification.CATEGORY_CALL) return true
        val isKnownCallPkg = CALL_PACKAGES.contains(pkg) ||
            pkg.contains("dialer") ||
            pkg.contains("telecom") ||
            pkg.contains("incallui")
        if (!isKnownCallPkg) return false

        val hasCallStyle = notification.extras?.containsKey("android.callPerson") == true ||
            notification.extras?.containsKey("android.callType") == true
        if (hasCallStyle) return true

        return notification.actions?.any { action ->
            val label = action.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
            label.contains("atender") ||
                label.contains("recusar") ||
                label.contains("rejeitar") ||
                label.contains("desligar") ||
                label.contains("answer") ||
                label.contains("decline") ||
                label.contains("reject") ||
                label.contains("hang up") ||
                label.contains("end call")
        } == true
    }

    private fun extractCallStateAndCaller(
        notification: Notification,
        extras: Bundle,
        pkg: String,
        bleManager: BajajBleManager
    ): Pair<Int, String>? {
        val actionLabels = notification.actions?.mapNotNull {
            it.title?.toString()?.lowercase(Locale.getDefault())
        } ?: emptyList()

        val hasAnswerAction = actionLabels.any {
            it.contains("atender") || it.contains("answer") || it.contains("aceitar") || it.contains("accept")
        }
        val hasEndOnlyAction = !hasAnswerAction && actionLabels.any {
            it.contains("desligar") || it.contains("encerrar") || it.contains("hang up") ||
                it.contains("end call") || it.contains("silenciar") || it.contains("mute") ||
                it.contains("speaker") || it.contains("alto-falante")
        }

        val rawCallType = extras.getInt("android.callType", -1)
        val textLower = (
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence("android.text")?.toString()
                ?: ""
            ).lowercase(Locale.getDefault())

        // Notification.CallStyle: CALL_TYPE_INCOMING = 1, CALL_TYPE_ONGOING = 2, CALL_TYPE_SCREENING = 3
        val callState = when {
            hasAnswerAction || rawCallType == 1 || textLower.contains("recebida") || textLower.contains("incoming") || textLower.contains("tocando") ->
                BajajProtocol.CALL_INCOMING
            hasEndOnlyAction || rawCallType == 2 || textLower.contains("em andamento") || textLower.contains("ongoing") ||
                "\\b\\d{1,2}:\\d{2}\\b".toRegex().containsMatchIn(textLower) ->
                BajajProtocol.CALL_ACTIVE
            notification.category == Notification.CATEGORY_CALL ->
                BajajProtocol.CALL_INCOMING
            else -> return null
        }

        if (callState == BajajProtocol.CALL_INCOMING) {
            val declineAction = notification.actions?.firstOrNull { action ->
                val label = action.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
                label.contains("recusar") || label.contains("rejeitar") || label.contains("decline") ||
                    label.contains("reject") || label.contains("desligar") || label.contains("hang up")
            }
            if (declineAction != null) {
                lastCallDeclineIntent = declineAction.actionIntent
            }
        }

        val candidates = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val callPerson = extras.get("android.callPerson") as? Person
            callPerson?.name?.toString()?.let { candidates.add(it) }

            @Suppress("DEPRECATION")
            val people = extras.getParcelableArrayList<Person>("android.people.list")
            people?.forEach { person ->
                person?.name?.toString()?.let { candidates.add(it) }
            }
        }

        extras.getCharSequence("android.conversationTitle")?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.let { candidates.add(it) }
        extras.getCharSequence("android.title")?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let { candidates.add(it) }

        val bestCandidate = candidates
            .map { cleanCandidateText(it) }
            .firstOrNull { !isGenericCallPlaceholder(it, pkg) }
            ?: ""

        val resolved = if (bestCandidate.isNotEmpty() && looksLikePhoneNumber(bestCandidate)) {
            bleManager.resolveContactNameIfPossible(bestCandidate) ?: bestCandidate
        } else {
            bestCandidate
        }

        return callState to resolved
    }

    private fun cleanCandidateText(raw: String): String {
        return raw.replace("[\\p{So}\\p{Cn}\\p{Cf}]".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun looksLikePhoneNumber(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        return digits >= 6 && text.all { it.isDigit() || it in setOf('+', '-', ' ', '(', ')') }
    }

    private fun isGenericCallPlaceholder(text: String, pkg: String): Boolean {
        if (text.isBlank()) return true
        val norm = text.lowercase(Locale.getDefault()).trim()
        if (norm == "whatsapp" || norm == "whatsapp business" || norm == "telefone" || norm == "phone") return true
        if ("^\\d{1,2}:\\d{2}(:\\d{2})?$".toRegex().matches(norm)) return true
        val genericPhrases = listOf(
            "chamada de voz",
            "chamada de vídeo",
            "chamada de video",
            "chamada recebida",
            "chamada em andamento",
            "chamada ativa",
            "ligação recebida",
            "ligacao recebida",
            "incoming call",
            "incoming voice call",
            "incoming video call",
            "voice call",
            "video call",
            "ongoing call",
            "calling",
            "ringing",
            "tocando"
        )
        if (genericPhrases.any { norm == it || norm.startsWith("$it ") || norm.startsWith("$it •") }) {
            return true
        }
        return pkg.contains("whatsapp") && norm.contains("chamada do whatsapp")
    }

    private fun handleMapsNavigationNotification(
        notification: Notification,
        extras: Bundle,
        bleManager: BajajBleManager
    ) {
        val title = (
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
                ?: extras.getCharSequence("android.title")?.toString()
                ?: ""
            ).trim()
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence("android.text")?.toString()
                ?: ""
            ).trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val subText = (
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                ?: extras.getCharSequence("android.subText")?.toString()
                ?: ""
            ).trim()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim() ?: ""
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() }
            ?: emptyList()

        val isOngoingOrNav = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (notification.flags and Notification.FLAG_NO_CLEAR) != 0 ||
            (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
            notification.category == Notification.CATEGORY_NAVIGATION

        if (GoogleMapsNotificationParser.isAmbientSummaryOnly(title, text, bigText)) {
            return
        }

        val isCounterClockwise = getPrefs().isRoundaboutCounterClockwise()
        val iconManeuver = GoogleMapsIconParser.extractManeuverFromNotification(
            notification = notification,
            extras = extras,
            context = this,
            counterClockwise = isCounterClockwise
        )

        val parsed = GoogleMapsNotificationParser.parse(
            title = title,
            text = text,
            subText = subText,
            bigText = bigText,
            textLines = textLines,
            infoText = infoText,
            fallbackTurnDistanceMeters = if (isMapsRouteActive) lastMapsTurnDistanceMeters else null,
            fallbackTotalDistanceMeters = if (isMapsRouteActive) lastMapsTotalDistanceMeters else null,
            fallbackSolidManeuver = if (isMapsRouteActive) lastMapsSolidManeuver else null,
            fallbackRoundaboutExit = if (isMapsRouteActive) lastMapsRoundaboutExit else 0,
            provider = NavProvider.GOOGLE_MAPS,
            roundaboutCounterClockwise = isCounterClockwise,
            iconManeuverCode = iconManeuver?.maneuverCode,
            iconRoundaboutExit = iconManeuver?.roundaboutExit ?: 0
        ) ?: return

        if (!parsed.isArrival && !isOngoingOrNav && (!parsed.isTurnDistanceExact || parsed.isAmbientStatusOnly)) {
            return
        }

        isMapsRouteActive = true
        lastMapsTurnDistanceMeters = parsed.distanceMeters
        lastMapsTotalDistanceMeters = parsed.totalDistanceMeters
        lastMapsSolidManeuver = parsed.solidManeuverCode
        lastMapsRoundaboutExit = parsed.roundaboutExit

        Log.d(
            TAG,
            "Google Maps TBT: dist=${parsed.distanceMeters}m etd=${parsed.totalDistanceMeters}m code=${parsed.maneuverCode.toInt().toChar()} exit=${parsed.roundaboutExit} arrival=${parsed.isArrival} street='${parsed.displayStreetText}'"
        )

        bleManager.onMapsNavigationAnchor(parsed, NavProvider.GOOGLE_MAPS)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val notification = sbn.notification ?: return
        val bleManager = appOrNull()?.bleManager ?: return

        val key = sbn.key
        if (key != null) {
            activeAlertSignatures.remove(key)
        }
        val wasTrackedCall = (key != null && activeCallKeys.remove(key)) ||
            (notification.category == Notification.CATEGORY_CALL &&
                (CALL_PACKAGES.contains(pkg) || pkg.contains("incallui") || pkg.contains("dialer") || pkg.contains("telecom")))

        if (wasTrackedCall && activeCallKeys.isEmpty()) {
            lastCallDeclineIntent = null
            bleManager.updateNotificationCallState(
                active = false,
                callState = BajajProtocol.CALL_NO_CALL,
                callerCandidate = "",
                sourceLabel = ""
            )
            return
        }

        if (pkg != MAPS_PACKAGE) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }
        if (hasActiveGoogleMapsNotification()) {
            return
        }

        val wasSessionActive = isMapsRouteActive
        resetMapsSession()
        if (wasSessionActive) {
            bleManager.onMapsNotificationCleared()
        }
    }

    private fun hasActiveGoogleMapsNotification(): Boolean {
        return try {
            activeNotifications?.any { active ->
                if (active.packageName != MAPS_PACKAGE) return@any false
                val n = active.notification ?: return@any false
                if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return@any false
                (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
                    (n.flags and Notification.FLAG_NO_CLEAR) != 0 ||
                    (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
                    n.category == Notification.CATEGORY_NAVIGATION
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun resetMapsSession() {
        isMapsRouteActive = false
        lastMapsTurnDistanceMeters = null
        lastMapsTotalDistanceMeters = null
        lastMapsSolidManeuver = null
        lastMapsRoundaboutExit = 0
    }

    private fun resolveAppLabel(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info)?.toString()?.trim().orEmpty().ifBlank { pkg }
        } catch (_: Exception) {
            pkg
        }
    }

    private fun isValidMessageAlert(title: String, text: String): Boolean {
        if (title.isEmpty() && text.isEmpty()) return false
        val normTitle = GoogleMapsNotificationParser.normalizeForMatch(title)
        val normText = GoogleMapsNotificationParser.normalizeForMatch(text)
        if (normText.matches("^\\d+\\s+(?:novas\\s+mensagens|mensagens|new\\s+messages).*$".toRegex())) {
            return false
        }
        if (normTitle.matches("^\\d+\\s+(?:conversas|chats).*$".toRegex())) {
            return false
        }
        val noisyStatus = listOf(
            "verificando novas mensagens",
            "checking for new messages",
            "fazendo backup",
            "backing up",
            "whatsapp web",
            "dispositivos conectados",
            "linked devices",
            "carregando mensagens",
            "loading messages",
            "conectado",
            "voce apagou esta mensagem",
            "mensagem apagada"
        )
        if (noisyStatus.any { normText.contains(it) || normTitle == it }) {
            return false
        }
        return true
    }

    private fun dispatchAlertDeduped(
        contentSig: String,
        appLabel: String,
        title: String,
        text: String,
        bleManager: BajajBleManager
    ) {
        val now = System.currentTimeMillis()
        val lastSent = recentAlertTimestamps[contentSig]
        if (lastSent != null && (now - lastSent) < 8000L) {
            return
        }
        recentAlertTimestamps[contentSig] = now
        if (recentAlertTimestamps.size > 32) {
            val oldest = recentAlertTimestamps.keys.firstOrNull()
            if (oldest != null) recentAlertTimestamps.remove(oldest)
        }
        bleManager.sendConfiguredAppAlert(appLabel, title, text)
    }
}
