package com.custom.ridenavlink

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

class MainActivity : Activity() {

    companion object {
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val KEY_CALLER_NAME = "caller_name"
        private const val KEY_ALERT_MSG = "alert_msg"
        private const val KEY_SCROLL_Y = "scroll_y"
        private const val KEY_BLINK_SIM = "nav_blink_sim"
        private const val KEY_ROUNDABOUT_SEL = "nav_roundabout_sel"
        private const val REQ_COMPANION_ALIAS = 201
    }

    private lateinit var app: BajajBridgeApp
    private lateinit var bleManager: BajajBleManager
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var preferences: AppPreferences
    private lateinit var scheme: M3ColorScheme

    private val discoveredDevices = LinkedHashMap<String, BluetoothDevice>()

    private var isWideLayout = false
    private var animationsEnabled = true

    // Estado persistente de UI
    private var activeTabIndex = 0
    private var savedCallerName = ""
    private var savedAlertMessage = ""
    private var savedScrollY = 0
    private var simulateBlinkNearTurn = false
    private var selectedRoundaboutExit = 2

    // Top App Bar & Status
    private lateinit var statusChipContainer: LinearLayout
    private lateinit var statusChipIcon: ImageView
    private lateinit var statusBadge: TextView
    private var lastStatusVisualKey: String? = null
    private var lastTelemetryUiKey: String? = null
    private var lastPinnedBikeSignature: String? = null
    private var lastDeviceListSignature: String? = null
    private var lastScheduleSignature: String? = null
    private var lastProgressPct: Int = -1

    // Expressive Virtual Cluster HUD
    private lateinit var hudClusterCard: LinearLayout
    private lateinit var hudClusterTag: TextView
    private lateinit var hudArrowIcon: ImageView
    private lateinit var hudDistanceValue: TextView
    private lateinit var hudDistanceUnit: TextView
    private lateinit var hudStreetIcon: ImageView
    private lateinit var hudStreetText: TextView
    private lateinit var hudManeuverSubtext: TextView
    private lateinit var hudSourceBadge: LinearLayout
    private lateinit var hudSourceBadgeIcon: ImageView
    private lateinit var hudSourceBadgeText: TextView
    private lateinit var hudCallBanner: LinearLayout
    private lateinit var hudCallerNameText: TextView
    private var arrowBlinkAnimator: ObjectAnimator? = null

    // Telemetria Dinâmica
    private lateinit var battIcon: ImageView
    private lateinit var battValueText: TextView
    private lateinit var battFillView: View
    private lateinit var battEmptyView: View
    private lateinit var signalIcon: ImageView
    private lateinit var signalValueText: TextView
    private val signalBars = mutableListOf<View>()
    private lateinit var callIcon: ImageView
    private lateinit var callValueText: TextView
    private lateinit var callStatusDot: View

    // Navegação & Conteúdo Adaptativo (3 abas: Cockpit, Ações e Perfil)
    private lateinit var rootLayout: FrameLayout
    private lateinit var compactScrollView: ScrollView
    private lateinit var compactContentColumn: LinearLayout
    private var wideLeftScrollView: ScrollView? = null
    private var wideRightScrollView: ScrollView? = null
    private var wideNavRail: LinearLayout? = null
    private var bottomNavWrapper: LinearLayout? = null

    private lateinit var tabCockpitContent: LinearLayout
    private lateinit var tabActionsContent: LinearLayout
    private lateinit var tabProfileContent: LinearLayout

    // Cockpit Views
    private lateinit var cockpitMaintenanceNoticeCard: LinearLayout
    private lateinit var cockpitMaintenanceNoticeIcon: ImageView
    private lateinit var cockpitMaintenanceNoticeTitle: TextView
    private lateinit var cockpitMaintenanceNoticeDetail: TextView
    private lateinit var cockpitMaintenanceNoticeChevron: ImageView
    private lateinit var pinnedBikeContainer: LinearLayout
    private lateinit var deviceContainer: LinearLayout
    private lateinit var scanDevicesButton: LinearLayout
    private lateinit var scanDevicesButtonLabel: TextView
    private lateinit var mapsListenerStatusText: TextView
    private lateinit var mapsLastCaptureText: TextView
    private lateinit var btnEnableListenerInCockpit: LinearLayout

    // Perfil & Manutenção Views
    private lateinit var profileAlertBanner: LinearLayout
    private lateinit var profileAlertIcon: ImageView
    private lateinit var profileAlertTitle: TextView
    private lateinit var profileAlertDetail: TextView
    private lateinit var profileAlertActionBtn: LinearLayout
    private lateinit var profileAlertActionLabel: TextView
    private lateinit var profileAvatarCircle: FrameLayout
    private lateinit var profileAvatarText: TextView
    private lateinit var profileAvatarIcon: ImageView
    private lateinit var profileRiderNameText: TextView
    private lateinit var profileBikeNameText: TextView
    private lateinit var profileBikeSubText: TextView
    private lateinit var profileOdometerValueText: TextView
    private lateinit var profileGpsStatusPill: LinearLayout
    private lateinit var profileGpsStatusIcon: ImageView
    private lateinit var profileGpsStatusText: TextView
    private lateinit var profileGpsNoticeText: TextView
    private lateinit var profileProgressFill: View
    private lateinit var profileProgressEmpty: View
    private lateinit var profileNextServiceKmText: TextView
    private lateinit var profileNextServiceRemText: TextView
    private lateinit var profileNextServiceDateText: TextView
    private lateinit var profileNextServiceDaysText: TextView
    private lateinit var profileGpsEnabledSwitch: Switch
    private lateinit var profileGpsOnlyConnectedSwitch: Switch
    private lateinit var profileScheduleListContainer: LinearLayout
    private var lastMaintenanceSnapshot: MaintenanceSnapshot? = null

    private data class AlertModeRowViews(
        val row: LinearLayout,
        val leadIcon: ImageView,
        val label: TextView,
        val desc: TextView,
        val checkBadge: ImageView
    )

    // Notificações & Testes (Ações)
    private lateinit var selectedAppsSummaryText: TextView
    private val alertModeRows = LinkedHashMap<PanelAlertMode, AlertModeRowViews>()
    private lateinit var callerEditText: EditText
    private lateinit var clearCallerButton: FrameLayout
    private lateinit var alertEditText: EditText
    private lateinit var clearAlertButton: FrameLayout
    private lateinit var alertByteCounterText: TextView

    // Configurações recomendadas & Diagnóstico
    private var pendingAliasAddress: String? = null
    private var pendingAliasOriginalName: String? = null
    private var pendingAliasValue: String? = null
    private lateinit var autoStartSwitch: Switch
    private lateinit var autoStartSubtitleText: TextView
    private lateinit var clusterControlsSwitch: Switch
    private lateinit var nativeSmsSwitch: Switch
    private lateinit var autoSmsRejectSwitch: Switch
    private lateinit var quickReplySmsSummaryText: TextView
    private lateinit var homeNavSummaryText: TextView
    private lateinit var preferredMediaSummaryText: TextView
    private lateinit var recBatteryStatusText: TextView
    private lateinit var recBatteryIcon: ImageView
    private lateinit var recListenerStatusText: TextView
    private lateinit var recListenerIcon: ImageView
    private lateinit var recContactsStatusText: TextView
    private lateinit var recContactsIcon: ImageView
    private lateinit var recSmsAndControlsStatusText: TextView
    private lateinit var recSmsAndControlsIcon: ImageView
    private lateinit var diagListenerText: TextView
    private lateinit var diagMapsText: TextView
    private lateinit var diagMusicText: TextView
    private lateinit var diagHeadsetVolText: TextView
    private lateinit var diagCallText: TextView
    private lateinit var diagAlertText: TextView
    private lateinit var diagControlsText: TextView
    private lateinit var diagBleText: TextView

    // Navegação / Manobras em Ações
    private lateinit var blinkOffChip: LinearLayout
    private lateinit var blinkOffLabel: TextView
    private lateinit var blinkOnChip: LinearLayout
    private lateinit var blinkOnLabel: TextView
    private val roundaboutChips = mutableListOf<LinearLayout>()
    private val roundaboutChipLabels = mutableListOf<TextView>()
    private val maneuverTileControllers = mutableListOf<() -> Unit>()

    private val navTabLabels = mutableListOf<TextView>()
    private val navTabPills = mutableListOf<LinearLayout>()
    private val navTabIcons = mutableListOf<ImageView>()
    private val navTabContainers = mutableListOf<LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.isForceDarkAllowed = false
        }

        app = BajajBridgeApp.get(this)
        bleManager = app.bleManager
        maintenanceManager = app.maintenanceManager
        preferences = app.preferences

        if (savedInstanceState != null) {
            activeTabIndex = savedInstanceState.getInt(KEY_ACTIVE_TAB, 0).coerceIn(0, 2)
            savedCallerName = savedInstanceState.getString(KEY_CALLER_NAME, "")
            savedAlertMessage = savedInstanceState.getString(KEY_ALERT_MSG, "")
            savedScrollY = savedInstanceState.getInt(KEY_SCROLL_Y, 0)
            simulateBlinkNearTurn = savedInstanceState.getBoolean(KEY_BLINK_SIM, false)
            selectedRoundaboutExit = savedInstanceState.getInt(KEY_ROUNDABOUT_SEL, 2).coerceIn(1, 7)
        }

        val requestedTab = intent?.getIntExtra(MaintenanceManager.EXTRA_OPEN_TAB, -1) ?: -1
        if (requestedTab in 0..2) {
            activeTabIndex = requestedTab
        }

        scheme = M3Theme.resolveColorScheme(this)
        animationsEnabled = M3Theme.areAnimationsEnabled(this)
        isWideLayout = resources.configuration.screenWidthDp >= 600

        if (preferences.isAutoStartEnabled()) {
            BajajForegroundService.startService(this)
        }
        promptBatteryOptimizationOnce()

        buildUserInterface()
        setupEdgeToEdgeWindow()
        rootLayout.requestApplyInsets()

        applyTabSelectionUi(activeTabIndex, animate = false)
        setupBleCallbacks()
        bleManager.pushCurrentStateToUi()
        maintenanceManager.refreshTrackingAndNotify()
        refreshMaintenanceUi(maintenanceManager.getSnapshot())
        refreshPinnedBikeUi()
        refreshSelectedAppsSummary()
        refreshAlertModeSelectionUi()
        refreshDiagnosticsUi()

        if (savedScrollY > 0) {
            val targetScroll = if (isWideLayout) wideRightScrollView else compactScrollView
            targetScroll?.post { targetScroll.scrollTo(0, savedScrollY) }
        }

        if (checkBlePermissions()) {
            val autoConnectingPinned = bleManager.tryAutoConnectPinned(preferPassiveAutoConnect = true)
            if (!autoConnectingPinned && !bleManager.isConnected()) {
                startScan()
            } else {
                refreshDeviceListUi()
            }
        } else {
            showDeviceEmptyState(
                message = "Permissões Bluetooth pendentes para localizar ou conectar ao painel da moto.",
                actionLabel = "Permitir acesso"
            ) {
                requestBlePermissions()
            }
            requestBlePermissions()
        }
    }

    override fun onStop() {
        super.onStop()
        bleManager.stopScan()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedTab = intent?.getIntExtra(MaintenanceManager.EXTRA_OPEN_TAB, -1) ?: -1
        if (requestedTab in 0..2 && activeTabIndex != requestedTab) {
            switchTab(requestedTab)
        }
    }

    override fun onResume() {
        super.onResume()
        val resolvedScheme = M3Theme.resolveColorScheme(this)
        if (
            resolvedScheme.isDark != scheme.isDark ||
            resolvedScheme.primary != scheme.primary ||
            resolvedScheme.surface != scheme.surface ||
            resolvedScheme.primaryContainer != scheme.primaryContainer ||
            resolvedScheme.surfaceContainer != scheme.surfaceContainer
        ) {
            recreate()
            return
        }
        animationsEnabled = M3Theme.areAnimationsEnabled(this)
        if (preferences.isAutoStartEnabled() && !BajajForegroundService.isRunning) {
            BajajForegroundService.startService(this)
        }
        MapsNotificationListener.requestRebindIfEnabled(this)
        maintenanceManager.refreshTrackingAndNotify()
        refreshMaintenanceUi(maintenanceManager.getSnapshot())
        refreshPinnedBikeUi()
        refreshSelectedAppsSummary()
        refreshDiagnosticsUi()
        bleManager.pushCurrentStateToUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_COMPANION_ALIAS) {
            val addr = pendingAliasAddress
            val orig = pendingAliasOriginalName ?: "Moto Bajaj"
            val targetValue = pendingAliasValue ?: ""
            pendingAliasAddress = null
            pendingAliasOriginalName = null
            pendingAliasValue = null

            if (resultCode == RESULT_OK && addr != null) {
                val res = bleManager.tryApplySystemBluetoothAlias(addr, targetValue)
                when (res) {
                    SystemAliasResult.APPLIED -> {
                        val shown = preferences.getDisplayName(addr, orig)
                        toast("Nome atualizado no app e no Bluetooth do celular: $shown")
                    }
                    SystemAliasResult.CLEARED -> {
                        toast("Nome original restaurado no app e no Bluetooth ($orig)")
                    }
                    else -> {
                        toast("Nome salvo no app (o sistema não permitiu alterar o Bluetooth)")
                    }
                }
            } else if (addr != null) {
                toast("Nome salvo apenas no aplicativo")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeTabIndex)
        if (::callerEditText.isInitialized) {
            outState.putString(KEY_CALLER_NAME, callerEditText.text?.toString() ?: "")
        }
        if (::alertEditText.isInitialized) {
            outState.putString(KEY_ALERT_MSG, alertEditText.text?.toString() ?: "")
        }
        val scrollY = if (isWideLayout) (wideRightScrollView?.scrollY ?: 0) else compactScrollView.scrollY
        outState.putInt(KEY_SCROLL_Y, scrollY)
        outState.putBoolean(KEY_BLINK_SIM, simulateBlinkNearTurn)
        outState.putInt(KEY_ROUNDABOUT_SEL, selectedRoundaboutExit)
    }

    // =========================================================================
    // 1. ARQUITETURA ADAPTATIVA (Compact vs Medium/Expanded) — 3 MENUS
    // =========================================================================
    private fun buildUserInterface() {
        navTabLabels.clear()
        navTabPills.clear()
        navTabIcons.clear()
        navTabContainers.clear()
        signalBars.clear()
        roundaboutChips.clear()
        roundaboutChipLabels.clear()
        maneuverTileControllers.clear()
        alertModeRows.clear()

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(scheme.surface)
        }

        tabCockpitContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (activeTabIndex == 0) View.VISIBLE else View.GONE
        }
        tabActionsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (activeTabIndex == 1) View.VISIBLE else View.GONE
        }
        tabProfileContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (activeTabIndex == 2) View.VISIBLE else View.GONE
        }

        if (isWideLayout) {
            buildWideAdaptiveShell()
        } else {
            buildCompactShell()
        }

        applyWindowInsets(rootLayout)
        setContentView(rootLayout)
    }

    private fun buildCompactShell() {
        compactScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        compactContentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(96))
        }
        compactScrollView.addView(compactContentColumn)
        rootLayout.addView(compactScrollView)

        buildTopAppBar(compactContentColumn)
        buildVirtualClusterCard(compactContentColumn)
        hudClusterCard.visibility = if (activeTabIndex == 2) View.GONE else View.VISIBLE

        buildTelemetryStrip(tabCockpitContent)
        buildCockpitTab(tabCockpitContent)
        buildActionsTab(tabActionsContent)
        buildProfileTab(tabProfileContent)

        compactContentColumn.addView(tabCockpitContent)
        compactContentColumn.addView(tabActionsContent)
        compactContentColumn.addView(tabProfileContent)

        buildDockedBottomNav(rootLayout)
    }

    private fun buildWideAdaptiveShell() {
        val horizontalRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Navigation Rail (3 menus: Cockpit, Ações e Perfil)
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(scheme.surfaceContainer)
            setPadding(dp(6), dp(16), dp(6), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        rail.addView(createNavItem("Cockpit", R.drawable.ic_nav_dashboard, 0, isRail = true))
        rail.addView(createNavItem("Ações", R.drawable.ic_nav_settings, 1, isRail = true))
        rail.addView(createNavItem("Perfil", R.drawable.ic_person, 2, isRail = true))
        wideNavRail = rail
        horizontalRoot.addView(rail)

        horizontalRoot.addView(View(this).apply {
            setBackgroundColor(scheme.outlineVariant)
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
        })

        // 2. Supporting Pane Esquerdo (TopBar + Cluster Virtual + Telemetria)
        val leftScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.44f)
        }
        val leftPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(12), dp(24))
        }
        buildTopAppBar(leftPane)
        buildVirtualClusterCard(leftPane)
        buildTelemetryStrip(leftPane)
        leftScroll.addView(leftPane)
        wideLeftScrollView = leftScroll
        horizontalRoot.addView(leftScroll)

        // 3. Pane Principal Direito
        val rightScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.56f)
        }
        val rightWrapper = FrameLayout(this).apply {
            setPadding(dp(12), dp(16), dp(20), dp(24))
        }
        val rightMaxColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        buildCockpitTab(tabCockpitContent)
        buildActionsTab(tabActionsContent)
        buildProfileTab(tabProfileContent)

        rightMaxColumn.addView(tabCockpitContent)
        rightMaxColumn.addView(tabActionsContent)
        rightMaxColumn.addView(tabProfileContent)
        rightWrapper.addView(rightMaxColumn)
        rightScroll.addView(rightWrapper)
        wideRightScrollView = rightScroll
        horizontalRoot.addView(rightScroll)

        rightWrapper.post {
            val maxPx = dp(620)
            if (rightMaxColumn.width > maxPx) {
                val lp = rightMaxColumn.layoutParams as FrameLayout.LayoutParams
                lp.width = maxPx
                lp.gravity = Gravity.CENTER_HORIZONTAL
                rightMaxColumn.layoutParams = lp
            }
        }

        rootLayout.addView(horizontalRoot)
    }

    private fun setupEdgeToEdgeWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (scheme.isDark) 0 else lightMask,
                lightMask
            )
        } else {
            @Suppress("DEPRECATION")
            var flags = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            if (!scheme.isDark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun applyWindowInsets(targetRoot: View) {
        targetRoot.setOnApplyWindowInsetsListener { _, insets ->
            val leftInset: Int
            val topInset: Int
            val rightInset: Int
            val bottomInset: Int
            val imeBottom: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                leftInset = sys.left
                topInset = sys.top
                rightInset = sys.right
                bottomInset = sys.bottom
                imeBottom = ime.bottom
            } else {
                @Suppress("DEPRECATION")
                leftInset = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                rightInset = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
                imeBottom = 0
            }

            val isImeOpen = imeBottom > bottomInset + dp(80)
            val effectiveBottom = maxOf(bottomInset, imeBottom)

            if (isWideLayout) {
                wideNavRail?.setPadding(leftInset + dp(8), topInset + dp(16), dp(8), bottomInset + dp(16))
                wideLeftScrollView?.setPadding(0, topInset, 0, effectiveBottom)
                wideRightScrollView?.setPadding(0, topInset, rightInset, effectiveBottom)
            } else {
                bottomNavWrapper?.let { nav ->
                    nav.visibility = if (isImeOpen) View.GONE else View.VISIBLE
                    nav.setPadding(leftInset, 0, rightInset, bottomInset)
                }
                val extraForNav = if (isImeOpen) dp(16) else dp(96)
                compactContentColumn.setPadding(
                    leftInset + dp(16),
                    topInset + dp(8),
                    rightInset + dp(16),
                    effectiveBottom + extraForNav
                )
            }

            insets
        }
    }

    // =========================================================================
    // 2. TOP APP BAR
    // =========================================================================
    private fun buildTopAppBar(parent: LinearLayout) {
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(4), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val brandIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_brand_mark)
            setColorFilter(scheme.primary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                setMargins(0, 0, dp(10), 0)
            }
        }
        appBar.addView(brandIcon)

        val title = TextView(this).apply {
            text = "RideNav Link"
            M3Theme.applyRole(this, M3TypeRole.HEADLINE, scheme.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        M3Theme.markAccessibilityHeading(title)
        appBar.addView(title)

        val statusHitArea = FrameLayout(this).apply {
            minimumHeight = dp(48)
            minimumWidth = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = "Estado da conexão Bluetooth"
            setOnClickListener {
                if (!checkBlePermissions()) {
                    requestBlePermissions()
                } else {
                    if (activeTabIndex != 0) {
                        switchTab(0)
                    }
                    if (!bleManager.isConnected() && !bleManager.isConnecting() && !bleManager.isPassiveWaiting()) {
                        bleManager.tryAutoConnectPinned(preferPassiveAutoConnect = false)
                    }
                    toast(bleManager.getStatusMessage())
                }
            }
        }

        statusChipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(32)
            setPadding(dp(10), dp(6), dp(12), dp(6))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(999).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary,
                normalStrokeColor = scheme.outlineVariant,
                normalStrokeWidthPx = dp(1)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        statusChipIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_bluetooth, 15, scheme.primary)
        (statusChipIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(6), 0)
        statusChipContainer.addView(statusChipIcon)

        statusBadge = TextView(this).apply {
            text = "Desconectado"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurface)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusChipContainer.addView(statusBadge)
        statusHitArea.addView(statusChipContainer)
        appBar.addView(statusHitArea)

        parent.addView(appBar)
    }

    // =========================================================================
    // 3. EXPRESSIVE HERO: PRÉVIA DO PAINEL (Reflete Maps/Testes)
    // =========================================================================
    private fun buildVirtualClusterCard(parent: LinearLayout) {
        hudClusterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = M3Theme.roundedSurface(
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(M3Shape.EXTRA_LARGE_DP).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(16)) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
        }

        hudClusterTag = TextView(this).apply {
            text = "Prévia do painel"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        M3Theme.markAccessibilityHeading(hudClusterTag)
        topRow.addView(hudClusterTag)

        hudSourceBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = M3Theme.roundedSurface(scheme.surfaceContainerHighest, dp(M3Shape.FULL_DP).toFloat())
        }
        hudSourceBadgeIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_map, 14, scheme.onSurfaceVariant)
        (hudSourceBadgeIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(5), 0)
        hudSourceBadgeText = TextView(this).apply {
            text = "Inativo"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
            includeFontPadding = false
            maxLines = 1
        }
        hudSourceBadge.addView(hudSourceBadgeIcon)
        hudSourceBadge.addView(hudSourceBadgeText)
        topRow.addView(hudSourceBadge)
        hudClusterCard.addView(topRow)

        val centerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
        }

        hudArrowIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_arrow_straight, 44, scheme.onSurfaceVariant)
        (hudArrowIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(16), 0)
        centerRow.addView(hudArrowIcon)

        val distCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val distRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        hudDistanceValue = TextView(this).apply {
            text = "--"
            M3Theme.applyRole(this, M3TypeRole.DISPLAY_NUMERAL, scheme.onSurface)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            maxLines = 1
        }
        distRow.addView(hudDistanceValue)

        hudDistanceUnit = TextView(this).apply {
            text = ""
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.primary)
            setPadding(dp(2), 0, 0, dp(4))
            includeFontPadding = false
            maxLines = 1
        }
        distRow.addView(hudDistanceUnit)
        distCol.addView(distRow)

        hudManeuverSubtext = TextView(this).apply {
            text = "Sem rota ativa"
            M3Theme.applyRole(this, M3TypeRole.BODY_MEDIUM, scheme.onSurfaceVariant)
            setPadding(0, dp(2), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        distCol.addView(hudManeuverSubtext)
        centerRow.addView(distCol)
        hudClusterCard.addView(centerRow)

        val streetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        hudStreetIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_location_pin, 18, scheme.onSurfaceVariant)
        (hudStreetIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(8), 0)
        streetRow.addView(hudStreetIcon)

        hudStreetText = TextView(this).apply {
            text = "Aguardando rota no Google Maps"
            M3Theme.applyRole(this, M3TypeRole.TITLE_SMALL, scheme.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        streetRow.addView(hudStreetText)
        hudClusterCard.addView(streetRow)

        hudCallBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = M3Theme.roundedSurface(scheme.errorContainer, dp(M3Shape.MEDIUM_DP).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(12), 0, 0) }
            visibility = View.GONE
        }

        val callBannerIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_phone_call, 18, scheme.onErrorContainer)
        (callBannerIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(8), 0)
        hudCallBanner.addView(callBannerIcon)

        hudCallerNameText = TextView(this).apply {
            text = "Chamada"
            M3Theme.applyRole(this, M3TypeRole.TITLE_SMALL, scheme.onErrorContainer)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        hudCallBanner.addView(hudCallerNameText)

        val callStateTag = TextView(this).apply {
            text = "Tocando"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onErrorContainer)
            maxLines = 1
        }
        hudCallBanner.addView(callStateTag)
        hudClusterCard.addView(hudCallBanner)

        parent.addView(hudClusterCard)
    }

    // =========================================================================
    // 4. FAIXA DE TELEMETRIA
    // =========================================================================
    private fun buildTelemetryStrip(parent: LinearLayout) {
        addSectionHeader(parent, "Telemetria")

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = M3Theme.roundedSurface(scheme.surfaceContainer, dp(M3Shape.LARGE_DP).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val col1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        battIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_battery, 18, scheme.onSurfaceVariant)
        col1.addView(battIcon)
        battValueText = TextView(this).apply {
            text = "100%"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(6), 0, dp(5))
        }
        col1.addView(battValueText)

        val barTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
            background = M3Theme.roundedSurface(scheme.outlineVariant, dp(2).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(4))
        }
        battFillView = View(this).apply {
            background = M3Theme.roundedSurface(scheme.primary, dp(2).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 100f)
        }
        battEmptyView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        }
        barTrack.addView(battFillView)
        barTrack.addView(battEmptyView)
        col1.addView(barTrack)

        val battLabel = TextView(this).apply {
            text = "Bateria"
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(5), 0, 0)
        }
        col1.addView(battLabel)
        strip.addView(col1)

        strip.addView(createVerticalHairline())

        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        signalIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_signal, 18, scheme.onSurfaceVariant)
        col2.addView(signalIcon)
        signalValueText = TextView(this).apply {
            text = "4/4"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(6), 0, dp(4))
        }
        col2.addView(signalValueText)

        val signalBarsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(10))
        }
        signalBars.clear()
        val heights = intArrayOf(dp(4), dp(6), dp(8), dp(10))
        for (i in 0..3) {
            val bar = View(this).apply {
                background = M3Theme.roundedSurface(scheme.primary, dp(1).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(4), heights[i]).apply {
                    setMargins(dp(1), 0, dp(1), 0)
                }
            }
            signalBars.add(bar)
            signalBarsRow.addView(bar)
        }
        col2.addView(signalBarsRow)

        val sigLabel = TextView(this).apply {
            text = "Sinal"
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }
        col2.addView(sigLabel)
        strip.addView(col2)

        strip.addView(createVerticalHairline())

        val col3 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        callIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_phone_call, 18, scheme.onSurfaceVariant)
        col3.addView(callIcon)
        callValueText = TextView(this).apply {
            text = "Livre"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(4))
        }
        col3.addView(callValueText)

        callStatusDot = View(this).apply {
            background = M3Theme.roundedSurface(scheme.outline, dp(4).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        col3.addView(callStatusDot)

        val callLabel = TextView(this).apply {
            text = "Chamadas"
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(5), 0, 0)
        }
        col3.addView(callLabel)
        strip.addView(col3)

        parent.addView(strip)
    }

    // =========================================================================
    // 5. ABA 1: COCKPIT (Sub-aviso Revisão + Moto Fixada + BLE + Maps + Notif)
    // =========================================================================
    private fun buildCockpitTab(parent: LinearLayout) {
        cockpitMaintenanceNoticeCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.warningContainer,
                radiusPx = dp(14).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { switchTab(2) }
        }
        applyTactileFeedback(cockpitMaintenanceNoticeCard)

        cockpitMaintenanceNoticeIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_warning, 18, scheme.onWarningContainer)
        (cockpitMaintenanceNoticeIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(10), 0)
        cockpitMaintenanceNoticeCard.addView(cockpitMaintenanceNoticeIcon)

        val noticeTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        cockpitMaintenanceNoticeTitle = TextView(this).apply {
            M3Theme.applyRole(this, M3TypeRole.TITLE_SMALL, scheme.onWarningContainer)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        noticeTextCol.addView(cockpitMaintenanceNoticeTitle)

        cockpitMaintenanceNoticeDetail = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onWarningContainer)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1), 0, 0)
        }
        noticeTextCol.addView(cockpitMaintenanceNoticeDetail)
        cockpitMaintenanceNoticeCard.addView(noticeTextCol)

        cockpitMaintenanceNoticeChevron = M3Theme.makeTintedIcon(this, R.drawable.ic_arrow_right, 18, scheme.onWarningContainer)
        cockpitMaintenanceNoticeCard.addView(cockpitMaintenanceNoticeChevron)
        parent.addView(cockpitMaintenanceNoticeCard)

        addSectionHeader(parent, "Moto fixada")
        pinnedBikeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
        parent.addView(pinnedBikeContainer)

        addSectionHeader(parent, "Dispositivos Bluetooth")
        deviceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
        parent.addView(deviceContainer)

        scanDevicesButton = createM3Button(
            btnText = "Buscar painel",
            iconRes = R.drawable.ic_sync,
            bgColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 8
        ) {
            if (checkBlePermissions()) {
                startScan()
                toast("Buscando painel da moto...")
            } else {
                requestBlePermissions()
            }
        }
        scanDevicesButtonLabel = scanDevicesButton.getChildAt(1) as TextView
        parent.addView(scanDevicesButton)

        addSectionHeader(parent, "Navegação (Google Maps)")
        val mapsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val mapsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
        }
        val mapsIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_map, 20, scheme.onSurfaceVariant)
        (mapsIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(10), 0)
        mapsHeader.addView(mapsIcon)

        val mapsTitle = TextView(this).apply {
            text = "Espelhamento automático"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        mapsHeader.addView(mapsTitle)
        mapsCard.addView(mapsHeader)

        mapsListenerStatusText = TextView(this).apply {
            text = "Verificando acesso às notificações..."
            M3Theme.applyType(this, 13f, bold = true, color = scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        }
        mapsCard.addView(mapsListenerStatusText)

        mapsLastCaptureText = TextView(this).apply {
            text = "Última captura: Nenhuma rota capturada"
            M3Theme.applyRole(this, M3TypeRole.BODY_SMALL, scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(14))
            setLineSpacing(0f, 1.25f)
        }
        mapsCard.addView(mapsLastCaptureText)

        val btnOpenMaps = createM3Button(
            btnText = "Abrir Google Maps",
            iconRes = R.drawable.ic_map,
            bgColor = scheme.primary,
            contentColor = scheme.onPrimary,
            rippleColor = scheme.rippleOnPrimary,
            bottomMarginDp = 8
        ) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=posto+de+gasolina")).apply {
                setPackage("com.google.android.apps.maps")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                toast("Google Maps não encontrado")
            }
        }
        mapsCard.addView(btnOpenMaps)

        /*
        // [FUTURAS MELHORIAS: MAPA LIVRE ALTERNATIVO (OSM / LEAFLET)]
        val btnOpenFreeMap = createM3Button(
            btnText = "Mapa Livre (OSM)",
            iconRes = R.drawable.ic_map,
            bgColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 8
        ) {
            openFreeCommercialMapDialog()
        }
        mapsCard.addView(btnOpenFreeMap)
        */

        btnEnableListenerInCockpit = createM3Button(
            btnText = "Ativar captura de notificações",
            iconRes = R.drawable.ic_notifications,
            bgColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 0
        ) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        mapsCard.addView(btnEnableListenerInCockpit)
        parent.addView(mapsCard)

        // ---------------------------------------------------------------------
        // NOTIFICAÇÕES DE APPS NO PAINEL (Aplicativos permitidos + Modo)
        // ---------------------------------------------------------------------
        addSectionHeader(parent, "Notificações no painel")

        val appSelectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val selectorRow = createListActionRow(
            title = "Aplicativos permitidos",
            subtitle = "Carregando aplicativos...",
            iconRes = R.drawable.ic_apps,
            iconTint = scheme.onSurfaceVariant
        ) {
            openInstalledAppsSelectorDialog()
        }
        val selectorTextCol = selectorRow.getChildAt(1) as LinearLayout
        selectedAppsSummaryText = selectorTextCol.getChildAt(1) as TextView
        appSelectorCard.addView(selectorRow)
        appSelectorCard.addView(createHorizontalHairline())

        val modeTitle = TextView(this).apply {
            text = "Tipo de exibição no painel da moto"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        appSelectorCard.addView(modeTitle)

        PanelAlertMode.values().forEachIndexed { index, mode ->
            if (index > 0) {
                appSelectorCard.addView(createHorizontalHairline())
            }
            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = M3Theme.interactiveSurface(
                    context = this@MainActivity,
                    bgColor = Color.TRANSPARENT,
                    radiusPx = 0f,
                    rippleColor = scheme.rippleOnSurface,
                    focusStrokeColor = scheme.primary
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    preferences.setPanelAlertMode(mode)
                    refreshAlertModeSelectionUi()
                    toast("Modo salvo: ${mode.shortLabel}")
                }
            }

            val iconRes = when (mode) {
                PanelAlertMode.BLINK_WAPP -> R.drawable.ic_chat_message
                PanelAlertMode.BLINK_SMS -> R.drawable.ic_sms
                PanelAlertMode.SOLID_ALERT -> R.drawable.ic_call_missed
            }
            val lead = M3Theme.makeTintedIcon(this, iconRes, 20, scheme.onSurfaceVariant)
            (lead.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(12), 0)
            modeRow.addView(lead)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    setMargins(0, 0, dp(8), 0)
                }
            }
            val labelTv = TextView(this).apply {
                text = mode.label
                M3Theme.applyType(this, 14f, bold = true, color = scheme.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(labelTv)

            val descTv = TextView(this).apply {
                text = mode.description
                M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            textCol.addView(descTv)
            modeRow.addView(textCol)

            val checkBadge = M3Theme.makeTintedIcon(this, R.drawable.ic_check, 20, scheme.onSecondaryContainer)
            modeRow.addView(checkBadge)

            alertModeRows[mode] = AlertModeRowViews(modeRow, lead, labelTv, descTv, checkBadge)
            appSelectorCard.addView(modeRow)
        }
        parent.addView(appSelectorCard)

        // ---------------------------------------------------------------------
        // CONFIGURAÇÕES RECOMENDADAS (Segundo plano, Bateria, Permissões)
        // ---------------------------------------------------------------------
        addSectionHeader(parent, "Configurações recomendadas")
        val recommendedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val autoStartRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = 0f,
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
        }
        val autoIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_sync, 22, scheme.onSurfaceVariant)
        (autoIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(14), 0)
        autoStartRow.addView(autoIcon)

        val autoTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(10), 0)
            }
        }
        autoTextCol.addView(TextView(this).apply {
            text = "Iniciar e rodar em segundo plano"
            M3Theme.applyType(this, 15f, bold = true, color = scheme.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        autoStartSubtitleText = TextView(this).apply {
            M3Theme.applyType(this, 13f, bold = false, color = scheme.onSurfaceVariant)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        autoTextCol.addView(autoStartSubtitleText)
        autoStartRow.addView(autoTextCol)

        autoStartSwitch = Switch(this).apply {
            isChecked = preferences.isAutoStartEnabled()
            val trackChecked = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(scheme.primary)
                setSize(dp(52), dp(32))
            }
            val trackUnchecked = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(scheme.surfaceContainerHighest)
                setStroke(dp(2), scheme.outline)
                setSize(dp(52), dp(32))
            }
            trackDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), trackChecked)
                addState(intArrayOf(), trackUnchecked)
            }
            val thumbCheckedShape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(scheme.onPrimary)
                setSize(dp(24), dp(24))
            }
            val thumbUncheckedShape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(scheme.outline)
                setSize(dp(16), dp(16))
            }
            thumbDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_checked),
                    android.graphics.drawable.InsetDrawable(thumbCheckedShape, dp(4))
                )
                addState(
                    intArrayOf(),
                    android.graphics.drawable.InsetDrawable(thumbUncheckedShape, dp(8))
                )
            }
            trackTintList = null
            thumbTintList = null
            contentDescription = "Ativar execução contínua em segundo plano"
            setOnCheckedChangeListener { _, checked ->
                preferences.setAutoStartEnabled(checked)
                if (checked) {
                    BajajForegroundService.startService(this@MainActivity)
                    if (checkBlePermissions()) {
                        bleManager.tryAutoConnectPinned(preferPassiveAutoConnect = true)
                    }
                    toast("Início automático e segundo plano ativados")
                } else {
                    stopService(Intent(this@MainActivity, BajajForegroundService::class.java))
                    toast("Segundo plano contínuo pausado")
                }
                refreshDiagnosticsUi()
            }
        }
        autoStartRow.addView(autoStartSwitch)
        autoStartRow.setOnClickListener { autoStartSwitch.toggle() }
        recommendedCard.addView(autoStartRow)
        recommendedCard.addView(createHorizontalHairline())

        val batteryRow = createRecommendedRow(
            title = "Bateria sem restrições",
            iconRes = R.drawable.ic_battery,
            iconTint = scheme.onSurfaceVariant
        ) {
            openBatteryOptimizationSettings(openDetailsIfAlreadyIgnored = true)
        }
        recBatteryStatusText = batteryRow.second
        recBatteryIcon = batteryRow.third
        recommendedCard.addView(batteryRow.first)
        recommendedCard.addView(createHorizontalHairline())

        val listenerRow = createRecommendedRow(
            title = "Notificações, Maps e WhatsApp",
            iconRes = R.drawable.ic_notifications,
            iconTint = scheme.onSurfaceVariant
        ) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        recListenerStatusText = listenerRow.second
        recListenerIcon = listenerRow.third
        recommendedCard.addView(listenerRow.first)
        recommendedCard.addView(createHorizontalHairline())

        val contactsRow = createRecommendedRow(
            title = "Nome do contato nas ligações",
            iconRes = R.drawable.ic_phone_call,
            iconTint = scheme.onSurfaceVariant
        ) {
            if (hasCallAndContactPermissions()) {
                toast("Permissões de chamadas e contatos já estão ativas")
            } else {
                requestCallAndContactPermissions()
            }
        }
        recContactsStatusText = contactsRow.second
        recContactsIcon = contactsRow.third
        recommendedCard.addView(contactsRow.first)
        recommendedCard.addView(createHorizontalHairline())

        val smsCtrlRow = createRecommendedRow(
            title = "Atender chamadas e SMS pelo guidão",
            iconRes = R.drawable.ic_sms,
            iconTint = scheme.onSurfaceVariant
        ) {
            if (hasSmsAndCallControlPermissions()) {
                toast("Permissões de atendimento e SMS já estão ativas")
            } else {
                requestSmsAndCallControlPermissions()
            }
        }
        recSmsAndControlsStatusText = smsCtrlRow.second
        recSmsAndControlsIcon = smsCtrlRow.third
        recommendedCard.addView(smsCtrlRow.first)
        recommendedCard.addView(createHorizontalHairline())

        recommendedCard.addView(createListActionRow(
            title = "Configurações do app no sistema",
            subtitle = "Em alguns celulares, defina Bateria como Irrestrito",
            iconRes = R.drawable.ic_nav_settings,
            iconTint = scheme.onSurfaceVariant
        ) {
            openAppDetailsSettings()
        })
        parent.addView(recommendedCard)

        // ---------------------------------------------------------------------
        // CONTROLES DO GUIDÃO, MÍDIA E SMS
        // ---------------------------------------------------------------------
        addSectionHeader(parent, "Controles e integrações")
        val integrationsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val controlsPair = createM3SwitchRow(
            title = "Comandos do guidão",
            subtitle = "Volume, atender ou recusar chamadas, música e ir para casa",
            iconRes = R.drawable.ic_two_wheeler,
            checked = preferences.isClusterControlsEnabled(),
            contentDesc = "Ativar ou desativar leitura de comandos do guidão"
        ) { checked ->
            preferences.setClusterControlsEnabled(checked)
            if (checked) bleManager.nudgeControlsPolling()
            toast(if (checked) "Comandos do guidão ativados" else "Comandos do guidão pausados")
            refreshDiagnosticsUi()
        }
        clusterControlsSwitch = controlsPair.second
        integrationsCard.addView(controlsPair.first)
        integrationsCard.addView(createHorizontalHairline())

        val nativeSmsPair = createM3SwitchRow(
            title = "Alertas de SMS",
            subtitle = "Mostra novas mensagens de texto diretamente no painel",
            iconRes = R.drawable.ic_sms,
            checked = preferences.isNativeSmsEnabled(),
            contentDesc = "Ativar alertas de SMS no painel"
        ) { checked ->
            preferences.setNativeSmsEnabled(checked)
            if (checked && checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                requestSmsAndCallControlPermissions()
            } else {
                toast(if (checked) "Alertas de SMS ativados" else "Alertas de SMS pausados")
            }
            refreshDiagnosticsUi()
        }
        nativeSmsSwitch = nativeSmsPair.second
        integrationsCard.addView(nativeSmsPair.first)
        integrationsCard.addView(createHorizontalHairline())

        val autoSmsRejectPair = createM3SwitchRow(
            title = "Enviar SMS automático ao recusar",
            subtitle = "Dispara resposta rápida ao recusar uma ligação pelo guidão",
            iconRes = R.drawable.ic_call_end,
            checked = preferences.isAutoSmsOnRejectEnabled(),
            contentDesc = "Ativar envio automático de SMS ao recusar chamada"
        ) { checked ->
            preferences.setAutoSmsOnRejectEnabled(checked)
            if (checked && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                requestSmsAndCallControlPermissions()
            } else {
                toast(if (checked) "Resposta automática por SMS ativada" else "Resposta automática desativada")
            }
            refreshDiagnosticsUi()
        }
        autoSmsRejectSwitch = autoSmsRejectPair.second
        integrationsCard.addView(autoSmsRejectPair.first)
        integrationsCard.addView(createHorizontalHairline())

        val quickSmsRow = createListActionRow(
            title = "Mensagem rápida de recusa",
            subtitle = preferences.getQuickReplySmsText(),
            iconRes = R.drawable.ic_chat_message,
            iconTint = scheme.onSurfaceVariant
        ) {
            openTextSettingDialog(
                title = "Mensagem rápida de recusa",
                subtitle = "Texto enviado via SMS quando você recusa uma ligação pelo guidão.",
                fieldHint = "Ex.: Estou pilotando, ligo assim que possível.",
                initialValue = preferences.getQuickReplySmsText(),
                neutralText = "Padrão"
            ) { newText ->
                preferences.setQuickReplySmsText(newText)
                refreshDiagnosticsUi()
                toast("Mensagem de recusa salva")
            }
        }
        quickReplySmsSummaryText = (quickSmsRow.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
        integrationsCard.addView(quickSmsRow)
        integrationsCard.addView(createHorizontalHairline())

        val homeNavRow = createListActionRow(
            title = "Destino \"Ir para casa\"",
            subtitle = preferences.getHomeNavigationQuery().ifBlank { "Não definido (busca \"casa\" no Maps)" },
            iconRes = R.drawable.ic_location_pin,
            iconTint = scheme.onSurfaceVariant
        ) {
            openTextSettingDialog(
                title = "Destino \"Ir para casa\"",
                subtitle = "Endereço ou local aberto no Google Maps ao acionar Ir para casa no painel.",
                fieldHint = "Rua, número, cidade ou CEP",
                initialValue = preferences.getHomeNavigationQuery(),
                neutralText = "Limpar"
            ) { newQuery ->
                preferences.setHomeNavigationQuery(newQuery)
                refreshDiagnosticsUi()
                toast(if (newQuery.isBlank()) "Destino padrão restaurado" else "Destino salvo")
            }
        }
        homeNavSummaryText = (homeNavRow.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
        integrationsCard.addView(homeNavRow)
        integrationsCard.addView(createHorizontalHairline())

        val mediaPkgRow = createListActionRow(
            title = "Player padrão de música",
            subtitle = resolveMediaPackageDisplay(preferences.getPreferredMediaPackage()),
            iconRes = R.drawable.ic_apps,
            iconTint = scheme.onSurfaceVariant
        ) {
            openPreferredMediaDialog()
        }
        preferredMediaSummaryText = (mediaPkgRow.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
        integrationsCard.addView(mediaPkgRow)

        parent.addView(integrationsCard)
    }

    private fun refreshPinnedBikeUi() {
        if (!::pinnedBikeContainer.isInitialized) return

        val pinnedAddr = preferences.getPinnedAddress()
        val pinnedName = preferences.getPinnedName()
        val isConnectedToPinned = pinnedAddr != null &&
            bleManager.isConnected() &&
            pinnedAddr.equals(bleManager.getConnectedAddress(), ignoreCase = true)
        val isConnectingPinned = pinnedAddr != null &&
            bleManager.isConnecting() &&
            pinnedAddr.equals(bleManager.getConnectedAddress(), ignoreCase = true)
        val isWaitingPinned = pinnedAddr != null &&
            bleManager.isPassiveWaiting() &&
            pinnedAddr.equals(bleManager.getConnectedAddress(), ignoreCase = true)

        val rawName = pinnedName ?: "Moto Bajaj"
        val customName = pinnedAddr?.let { preferences.getCustomDeviceName(it) }
        val displayName = pinnedAddr?.let { preferences.getDisplayName(it, rawName) } ?: ""

        val signature = "$pinnedAddr|$rawName|$displayName|$isConnectedToPinned|$isConnectingPinned|$isWaitingPinned"
        if (signature == lastPinnedBikeSignature) return
        lastPinnedBikeSignature = signature

        pinnedBikeContainer.removeAllViews()

        if (pinnedAddr == null) {
            val emptyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val icon = M3Theme.makeTintedIcon(this, R.drawable.ic_pin, 20, scheme.onSurfaceVariant)
            (icon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(12), 0)
            emptyRow.addView(icon)

            val txt = TextView(this).apply {
                text = "Nenhuma moto fixada. Toque em Fixar em um dispositivo abaixo para salvar e reconectar automaticamente."
                M3Theme.applyType(this, 13f, bold = false, color = scheme.onSurfaceVariant)
                setLineSpacing(0f, 1.25f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            emptyRow.addView(txt)
            pinnedBikeContainer.addView(emptyRow)
            return
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val bikeIcon = M3Theme.makeTintedIcon(
            this,
            R.drawable.ic_two_wheeler,
            24,
            if (isConnectedToPinned) scheme.tertiary else scheme.primary
        ).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(12), 0)
        }
        infoRow.addView(bikeIcon)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(6), 0)
            }
        }
        val nameTv = TextView(this).apply {
            text = displayName
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(nameTv)

        val statusLabel = when {
            isConnectedToPinned -> "Conectada • reconexão automática ativa"
            isConnectingPinned -> "Conectando..."
            isWaitingPinned -> "Aguardando moto ligar"
            else -> "$pinnedAddr • desconectada"
        }
        val fullSub = if (customName != null && !customName.equals(rawName, ignoreCase = true)) {
            "$statusLabel • Painel: $rawName"
        } else {
            statusLabel
        }
        val subTv = TextView(this).apply {
            text = fullSub
            M3Theme.applyType(
                this,
                12f,
                bold = isConnectedToPinned,
                color = if (isConnectedToPinned) scheme.tertiary else scheme.onSurfaceVariant
            )
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        col.addView(subTv)
        infoRow.addView(col)

        val renameBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(24).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Alterar nome da moto"
            setOnClickListener {
                openRenameDeviceDialog(pinnedAddr, rawName)
            }
        }
        val renameIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_edit, 20, scheme.onSurfaceVariant)
        renameIcon.layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER }
        renameBtn.addView(renameIcon)
        infoRow.addView(renameBtn)

        body.addView(infoRow)

        val btnRow = createTwoColRow(bottomMarginDp = 0)
        val connectBtn = createM3Button(
            btnText = if (isConnectedToPinned || isConnectingPinned) "Desconectar" else "Conectar agora",
            iconRes = if (isConnectedToPinned || isConnectingPinned) R.drawable.ic_call_end else R.drawable.ic_bluetooth,
            bgColor = if (isConnectedToPinned || isConnectingPinned) scheme.secondaryContainer else scheme.primary,
            contentColor = if (isConnectedToPinned || isConnectingPinned) scheme.onSecondaryContainer else scheme.onPrimary,
            rippleColor = if (isConnectedToPinned || isConnectingPinned) scheme.rippleOnSurface else scheme.rippleOnPrimary,
            bottomMarginDp = 0
        ) {
            if (isConnectedToPinned || isConnectingPinned) {
                bleManager.disconnect(userInitiated = true)
                toast("Desconectado manualmente")
            } else {
                if (!checkBlePermissions()) {
                    requestBlePermissions()
                } else {
                    bleManager.connectByAddress(pinnedAddr, rawName, autoConnect = false, isReconnect = false)
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }

        val unpinBtn = createM3Button(
            btnText = "Desafixar",
            iconRes = R.drawable.ic_pin,
            bgColor = Color.TRANSPARENT,
            contentColor = scheme.error,
            rippleColor = scheme.rippleOnSurface,
            strokeColor = scheme.outline,
            bottomMarginDp = 0
        ) {
            preferences.unpinDevice()
            refreshPinnedBikeUi()
            refreshDeviceListUi()
            refreshMaintenanceUi(maintenanceManager.getSnapshot())
            toast("Moto desafixada")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
        btnRow.addView(connectBtn)
        btnRow.addView(unpinBtn)
        body.addView(btnRow)

        pinnedBikeContainer.addView(body)
    }

    private fun showDeviceEmptyState(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        lastDeviceListSignature = null
        deviceContainer.removeAllViews()
        val emptyBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_bluetooth, 20, scheme.onSurfaceVariant)
        (btIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(12), 0)
        row.addView(btIcon)

        val msgText = TextView(this).apply {
            text = message
            M3Theme.applyType(this, 14f, bold = false, color = scheme.onSurfaceVariant)
            setLineSpacing(0f, 1.2f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        row.addView(msgText)
        emptyBlock.addView(row)

        if (actionLabel != null && onAction != null) {
            val actionBtn = createM3Button(
                btnText = actionLabel,
                iconRes = R.drawable.ic_bluetooth,
                bgColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
                rippleColor = scheme.rippleOnSurface,
                bottomMarginDp = 0
            ) {
                onAction()
            }
            (actionBtn.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(12), 0, 0)
            emptyBlock.addView(actionBtn)
        }
        deviceContainer.addView(emptyBlock)
    }

    private fun refreshDeviceListUi() {
        if (!::deviceContainer.isInitialized) return
        if (discoveredDevices.isEmpty()) {
            showDeviceEmptyState(
                message = "Toque em Buscar painel abaixo para procurar sua moto por perto."
            )
            return
        }

        val sorted = discoveredDevices.values.sortedByDescending { dev ->
            val n = dev.name ?: ""
            val isBajaj = n.contains("DOMINAR", ignoreCase = true) ||
                n.contains("BAJAJ", ignoreCase = true) ||
                n.contains("PULSAR", ignoreCase = true)
            val pinnedBonus = if (preferences.isPinned(dev.address)) 2 else 0
            pinnedBonus + if (isBajaj) 1 else 0
        }

        val devSig = sorted.joinToString("|") { d ->
            "${d.address}:${preferences.getDisplayName(d.address, d.name)}:${preferences.isPinned(d.address)}"
        }
        if (devSig == lastDeviceListSignature) return
        lastDeviceListSignature = devSig

        deviceContainer.removeAllViews()
        sorted.forEachIndexed { idx, device ->
            if (idx > 0) {
                deviceContainer.addView(createHorizontalHairline())
            }
            deviceContainer.addView(buildDeviceRow(device))
        }
    }

    private fun buildDeviceRow(device: BluetoothDevice): LinearLayout {
        val bleName = device.name?.takeIf { it.isNotBlank() } ?: "Dispositivo Bluetooth"
        val addr = device.address
        val displayName = preferences.getDisplayName(addr, bleName)
        val customName = preferences.getCustomDeviceName(addr)
        val isBajaj = bleName.contains("DOMINAR", ignoreCase = true) ||
            bleName.contains("BAJAJ", ignoreCase = true) ||
            bleName.contains("PULSAR", ignoreCase = true)
        val isPinned = preferences.isPinned(addr)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = M3Theme.roundedSurface(
                bgColor = if (isBajaj && !isPinned) scheme.surfaceContainer else Color.TRANSPARENT,
                radiusPx = 0f
            )
            isLongClickable = true
            setOnLongClickListener {
                openRenameDeviceDialog(addr, bleName)
                true
            }
        }

        val leadIcon = if (isBajaj || isPinned) {
            M3Theme.makeTintedIcon(
                this,
                R.drawable.ic_two_wheeler,
                24,
                scheme.primary
            ).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(12), 0)
            }
        } else {
            M3Theme.makeTintedIcon(this, R.drawable.ic_bluetooth, 20, scheme.onSurfaceVariant).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(12), 0)
            }
        }
        row.addView(leadIcon)

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(6), 0)
            }
        }
        val devName = TextView(this).apply {
            text = if (isPinned) "$displayName (Fixada)" else displayName
            M3Theme.applyType(this, 14f, bold = true, color = scheme.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        infoCol.addView(devName)

        val devAddr = TextView(this).apply {
            text = if (customName != null && !customName.equals(bleName, ignoreCase = true)) {
                "$addr • Original: $bleName"
            } else {
                addr
            }
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        infoCol.addView(devAddr)
        row.addView(infoCol)

        // Botão Fixar / Fixada (48dp x 48dp)
        val pinBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(0, 0, dp(4), 0)
            }
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = if (isPinned) scheme.primaryContainer else Color.TRANSPARENT,
                radiusPx = dp(24).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = if (isPinned) "Desafixar $displayName" else "Fixar $displayName"
            setOnClickListener {
                if (isPinned) {
                    preferences.unpinDevice()
                    toast("$displayName desafixada")
                } else {
                    preferences.pinDevice(addr, bleName)
                    toast("$displayName fixada como moto principal")
                }
                refreshPinnedBikeUi()
                refreshDeviceListUi()
                refreshMaintenanceUi(maintenanceManager.getSnapshot())
            }
        }
        val pinIcon = M3Theme.makeTintedIcon(
            this,
            R.drawable.ic_pin,
            19,
            if (isPinned) scheme.onPrimaryContainer else scheme.onSurfaceVariant
        )
        pinIcon.layoutParams = FrameLayout.LayoutParams(dp(19), dp(19)).apply { gravity = Gravity.CENTER }
        pinBtn.addView(pinIcon)
        row.addView(pinBtn)

        // Botão Conectar (alvo >= 48dp altura)
        val connectBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumWidth = dp(76)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.secondaryContainer,
                radiusPx = dp(999).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Conectar a $displayName"
            setOnClickListener {
                bleManager.connect(device)
                refreshPinnedBikeUi()
            }
        }
        val connectTxt = TextView(this).apply {
            text = "Conectar"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSecondaryContainer)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        connectBtn.addView(connectTxt)
        row.addView(connectBtn)

        return row
    }

    // =========================================================================
    // 6. ABA 2: AÇÕES (Notificações, Seletor de Apps, Testes de Manobra, Chamada)
    // =========================================================================
    private data class ManeuverSpec(
        val title: String,
        val solidCode: Byte,
        val iconRes: Int,
        val solidDistMeters: Int,
        val streetSample: String,
        val etdSampleMeters: Int,
        val supportsBlink: Boolean = true,
        val usesRoundaboutExit: Boolean = false,
        val isAlertAccent: Boolean = false,
        val hardwareNote: String? = null
    )

    private fun buildActionsTab(parent: LinearLayout) {
        // ---------------------------------------------------------------------
        // SEÇÃO A: TESTAR ALERTAS NO PAINEL
        // ---------------------------------------------------------------------
        addSectionHeader(parent, "Testar alertas no painel")

        // Compositor de teste de alerta
        val alertComposerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val alertHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val alertLabel = TextView(this).apply {
            text = "Testar alerta no painel"
            M3Theme.applyType(this, 12f, bold = true, color = scheme.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        alertHeaderRow.addView(alertLabel)

        alertByteCounterText = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = true, color = scheme.onSurfaceVariant)
            fontFeatureSettings = "tnum"
        }
        alertHeaderRow.addView(alertByteCounterText)
        alertComposerCard.addView(alertHeaderRow)

        val alertFieldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            background = M3Theme.textFieldSurface(this@MainActivity, scheme, dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }

        alertEditText = EditText(this).apply {
            setText(savedAlertMessage)
            setTextColor(scheme.onSurface)
            setHintTextColor(scheme.onSurfaceVariant)
            hint = "Remetente ou mensagem (até 32 letras)"
            M3Theme.applyType(this, 15f, bold = false, color = scheme.onSurface)
            background = null
            setPadding(dp(14), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = "Mensagem para testar alerta no painel"
        }
        alertFieldRow.addView(alertEditText)

        clearAlertButton = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(24).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Limpar texto do alerta"
            visibility = if (savedAlertMessage.isNotEmpty()) View.VISIBLE else View.GONE
            setOnClickListener {
                alertEditText.setText("")
                alertEditText.requestFocus()
            }
        }
        val clearAlertIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_call_end, 18, scheme.onSurfaceVariant)
        clearAlertIcon.layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER }
        clearAlertButton.addView(clearAlertIcon)
        alertFieldRow.addView(clearAlertButton)

        val updateAlertCounter = { raw: String ->
            val bytes = BajajProtocol.utf8ByteLength(raw.trim())
            alertByteCounterText.text = "$bytes/${BajajProtocol.MAX_ALERT_BYTES}"
            val counterColor = when {
                bytes > BajajProtocol.MAX_ALERT_BYTES -> scheme.error
                bytes >= 28 -> scheme.warning
                else -> scheme.onSurfaceVariant
            }
            alertByteCounterText.setTextColor(counterColor)
        }
        updateAlertCounter(savedAlertMessage)

        alertEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val txt = s?.toString() ?: ""
                clearAlertButton.visibility = if (txt.isEmpty()) View.GONE else View.VISIBLE
                updateAlertCounter(txt)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        alertEditText.setOnFocusChangeListener { _, hasFocus ->
            alertFieldRow.isSelected = hasFocus
        }
        alertComposerCard.addView(alertFieldRow)

        val btnSendSelectedMode = createM3Button(
            btnText = "Enviar alerta de teste",
            iconRes = R.drawable.ic_notifications,
            bgColor = scheme.primary,
            contentColor = scheme.onPrimary,
            rippleColor = scheme.rippleOnPrimary,
            bottomMarginDp = 8
        ) {
            val msg = alertEditText.text.toString().trim().ifEmpty { "Teste RideNav Link" }
            bleManager.sendConfiguredAppAlert("Teste", msg, "")
            toast("Alerta de teste enviado para o painel")
        }
        alertComposerCard.addView(btnSendSelectedMode)

        val alertBtnRow = createTwoColRow(bottomMarginDp = 0)
        val btnSendBlink = createM3Button(
            btnText = "Alerta piscante",
            iconRes = R.drawable.ic_chat_message,
            bgColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 0
        ) {
            val msg = alertEditText.text.toString().trim().ifEmpty { "Chegando em 5 min" }
            bleManager.sendAlertNotification(BajajProtocol.ALERT_WHATSAPP, msg)
            toast("Alerta piscante enviado para o painel")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }
        val btnSendSolid = createM3Button(
            btnText = "Alerta fixo",
            iconRes = R.drawable.ic_sms,
            bgColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 0
        ) {
            val msg = alertEditText.text.toString().trim().ifEmpty { "Alerta fixo" }
            bleManager.sendMissedCall(msg, "Painel")
            toast("Alerta fixo enviado para o painel")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
        alertBtnRow.addView(btnSendBlink)
        alertBtnRow.addView(btnSendSolid)
        alertComposerCard.addView(alertBtnRow)

        val btnClearAlerts = createM3Button(
            btnText = "Limpar alertas no painel",
            iconRes = R.drawable.ic_call_end,
            bgColor = Color.TRANSPARENT,
            contentColor = scheme.onSurface,
            rippleColor = scheme.rippleOnSurface,
            strokeColor = scheme.outline,
            bottomMarginDp = 0
        ) {
            bleManager.clearPanelAlerts()
            toast("Comando para limpar alertas enviado")
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(8), 0, 0)
        }
        alertComposerCard.addView(btnClearAlerts)
        parent.addView(alertComposerCard)

        // ---------------------------------------------------------------------
        // SEÇÃO B: TESTES DE NAVEGAÇÃO & MANOBRAS (Movido da antiga aba Navegação)
        // ---------------------------------------------------------------------
        addSectionHeader(parent, "Testes de navegação e manobras")

        val navInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(4), dp(2), dp(4), dp(10))
        }
        val navInfoIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_map, 18, scheme.onSurfaceVariant)
        (navInfoIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), dp(10), 0)
        navInfoRow.addView(navInfoIcon)

        val navInfoText = TextView(this).apply {
            text = "Toque em qualquer manobra para testar no painel da moto. Perto da curva (menos de 100 m), a seta pisca para chamar atenção. Algumas motos não exibem ícones de balsa ou trem no visor."
            M3Theme.applyType(this, 13f, bold = false, color = scheme.onSurfaceVariant)
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        navInfoRow.addView(navInfoText)
        parent.addView(navInfoRow)

        val modeRow = createTwoColRow(bottomMarginDp = 8)
        val offPair = createSegmentedChoiceChip("Fixa (>= 100 m)", isLeft = true) {
            if (simulateBlinkNearTurn) {
                simulateBlinkNearTurn = false
                refreshNavigationModeUi()
            }
        }
        blinkOffChip = offPair.first
        blinkOffLabel = offPair.second

        val onPair = createSegmentedChoiceChip("Aproximação (< 100 m)", isLeft = false) {
            if (!simulateBlinkNearTurn) {
                simulateBlinkNearTurn = true
                refreshNavigationModeUi()
            }
        }
        blinkOnChip = onPair.first
        blinkOnLabel = onPair.second
        modeRow.addView(blinkOffChip)
        modeRow.addView(blinkOnChip)
        parent.addView(modeRow)

        addSectionHeader(parent, "Saída na rotatória (1ª a 7ª)")
        val exitScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
        }
        val exitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (exitNum in 1..7) {
            val chipLabel = TextView(this).apply {
                text = "${exitNum}ª"
                M3Theme.applyRole(this, M3TypeRole.LABEL_LARGE, scheme.onSurface)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
            }
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (exitNum < 7) setMargins(0, 0, dp(8), 0)
                }
                isClickable = true
                isFocusable = true
                contentDescription = "Saída ${exitNum} na rotatória"
                setOnClickListener {
                    selectedRoundaboutExit = exitNum
                    refreshRoundaboutExitUi()
                    refreshManeuverTilesUi()
                    toast("Rotatória ajustada para ${exitNum}ª saída")
                }
            }
            chip.addView(chipLabel)
            roundaboutChips.add(chip)
            roundaboutChipLabels.add(chipLabel)
            exitRow.addView(chip)
        }
        exitScroll.addView(exitRow)
        parent.addView(exitScroll)

        addSectionHeader(parent, "Manobras direcionais")
        val specs = listOf(
            ManeuverSpec("Frente", BajajProtocol.ARROW_STRAIGHT_SOLID, R.drawable.ic_arrow_straight, 850, "Av. das Nações", 3400),
            ManeuverSpec("Convergência", BajajProtocol.ARROW_MERGE, R.drawable.ic_arrow_merge, 200, "Convergência na via", 2800, supportsBlink = false),
            ManeuverSpec("Esquerda", BajajProtocol.ARROW_LEFT_SOLID, R.drawable.ic_arrow_left, 300, "Vire à esquerda", 2400),
            ManeuverSpec("Direita", BajajProtocol.ARROW_RIGHT_SOLID, R.drawable.ic_arrow_right, 300, "Vire à direita", 2400),
            ManeuverSpec("Leve esquerda", BajajProtocol.ARROW_SLIGHT_LEFT_SOLID, R.drawable.ic_arrow_slight_left, 150, "Mantenha leve à esquerda", 1900),
            ManeuverSpec("Leve direita", BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID, R.drawable.ic_arrow_slight_right, 150, "Mantenha leve à direita", 1900),
            ManeuverSpec("Acentuada esq.", BajajProtocol.ARROW_SHARP_LEFT_SOLID, R.drawable.ic_arrow_sharp_left, 180, "Curva acentuada à esquerda", 1700),
            ManeuverSpec("Acentuada dir.", BajajProtocol.ARROW_SHARP_RIGHT_SOLID, R.drawable.ic_arrow_sharp_right, 180, "Curva acentuada à direita", 1700),
            ManeuverSpec("Manter esq.", BajajProtocol.ARROW_KEEP_LEFT_SOLID, R.drawable.ic_arrow_slight_left, 250, "Mantenha-se à esquerda", 1500),
            ManeuverSpec("Manter dir.", BajajProtocol.ARROW_KEEP_RIGHT_SOLID, R.drawable.ic_arrow_slight_right, 250, "Mantenha-se à direita", 1500),
            ManeuverSpec("Bifurcação esq.", BajajProtocol.ARROW_FORK_LEFT_SOLID, R.drawable.ic_arrow_fork_left, 220, "Bifurcação à esquerda", 1300),
            ManeuverSpec("Bifurcação dir.", BajajProtocol.ARROW_FORK_RIGHT_SOLID, R.drawable.ic_arrow_fork_right, 220, "Bifurcação à direita", 1300),
            ManeuverSpec("Alça esquerda", BajajProtocol.ARROW_RAMP_LEFT_SOLID, R.drawable.ic_arrow_ramp_left, 250, "Alça de acesso à esquerda", 1100),
            ManeuverSpec("Alça direita", BajajProtocol.ARROW_RAMP_RIGHT_SOLID, R.drawable.ic_arrow_ramp_right, 250, "Saída à direita", 1100),
            ManeuverSpec("Retorno esq.", BajajProtocol.ARROW_UTURN_LEFT_SOLID, R.drawable.ic_arrow_uturn, 200, "Retorno à esquerda", 950, isAlertAccent = true),
            ManeuverSpec("Retorno dir.", BajajProtocol.ARROW_UTURN_RIGHT_SOLID, R.drawable.ic_arrow_uturn_right, 200, "Retorno à direita", 950, isAlertAccent = true),
            ManeuverSpec("Rotatória dir.", BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID, R.drawable.ic_arrow_roundabout, 150, "Rotatória à direita", 800, usesRoundaboutExit = true),
            ManeuverSpec("Rotatória esq.", BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID, R.drawable.ic_arrow_roundabout_left, 150, "Rotatória à esquerda", 800, usesRoundaboutExit = true),
            ManeuverSpec("Balsa", BajajProtocol.ARROW_FERRY_SOLID, R.drawable.ic_ferry, 400, "Travessia de balsa", 1200, hardwareNote = "Sem ícone no painel"),
            ManeuverSpec("Balsa / Trem", BajajProtocol.ARROW_FERRY_TRAIN_SOLID, R.drawable.ic_train, 400, "Embarque ferroviário", 1200, supportsBlink = false, hardwareNote = "Sem ícone no painel"),
            ManeuverSpec("Contramão", BajajProtocol.ARROW_WRONG_WAY, R.drawable.ic_wrong_way, 120, "Recalculando rota", 1200, supportsBlink = false, isAlertAccent = true),
            ManeuverSpec("Destino", BajajProtocol.ARROW_DESTINATION_SOLID, R.drawable.ic_flag_destination, 200, "Chegada ao destino", 200)
        )

        var idx = 0
        while (idx < specs.size) {
            val row = createTwoColRow()
            row.addView(createDynamicManeuverTile(specs[idx], isLeft = true))
            if (idx + 1 < specs.size) {
                row.addView(createDynamicManeuverTile(specs[idx + 1], isLeft = false))
            } else {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f).apply {
                        setMargins(dp(4), 0, 0, 0)
                    }
                })
            }
            parent.addView(row)
            idx += 2
        }

        refreshNavigationModeUi()
        refreshRoundaboutExitUi()

        val navEndRow = createTwoColRow(bottomMarginDp = 8)
        val btnDest = createM3Button(
            btnText = "Chegada (30m)",
            iconRes = R.drawable.ic_flag_destination,
            bgColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 0
        ) {
            bleManager.sendNavigation(
                distanceMeters = 30,
                maneuverCode = BajajProtocol.ARROW_DESTINATION_BLINK,
                text = "Destino atingido",
                totalDistanceMeters = 30,
                sourceLabel = "Teste"
            )
            toast("Chegada ao destino enviada")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }
        val btnStopNav = createM3Button(
            btnText = "Encerrar rota",
            iconRes = R.drawable.ic_call_end,
            bgColor = Color.TRANSPARENT,
            contentColor = scheme.error,
            rippleColor = scheme.rippleOnSurface,
            strokeColor = scheme.outline,
            bottomMarginDp = 0
        ) {
            bleManager.stopNavigation()
            toast("Navegação encerrada")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
        navEndRow.addView(btnDest)
        navEndRow.addView(btnStopNav)
        parent.addView(navEndRow)

        // ---------------------------------------------------------------------
        // SEÇÃO C: SIMULADOR DE CHAMADA & TELEMETRIA
        // ---------------------------------------------------------------------
        addSectionHeader(parent, "Simulador de chamada e telemetria")

        val callCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val callLabel = TextView(this).apply {
            text = "Nome ou número no painel"
            M3Theme.applyType(this, 12f, bold = true, color = scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(6))
        }
        callCard.addView(callLabel)

        val fieldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            background = M3Theme.textFieldSurface(this@MainActivity, scheme, dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }

        callerEditText = EditText(this).apply {
            setText(savedCallerName)
            setTextColor(scheme.onSurface)
            setHintTextColor(scheme.onSurfaceVariant)
            hint = "Digite o nome do contato"
            M3Theme.applyType(this, 16f, bold = false, color = scheme.onSurface)
            background = null
            setPadding(dp(14), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = "Nome ou número do contato para simular chamada"
        }
        fieldRow.addView(callerEditText)

        clearCallerButton = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(24).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Limpar nome do contato"
            visibility = if (savedCallerName.isNotEmpty()) View.VISIBLE else View.GONE
            setOnClickListener {
                callerEditText.setText("")
                callerEditText.requestFocus()
            }
        }
        val clearIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_call_end, 18, scheme.onSurfaceVariant)
        clearIcon.layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER }
        clearCallerButton.addView(clearIcon)
        fieldRow.addView(clearCallerButton)

        callerEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearCallerButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        callerEditText.setOnFocusChangeListener { _, hasFocus ->
            fieldRow.isSelected = hasFocus
        }
        callCard.addView(fieldRow)

        val callBtnRow = createTwoColRow(bottomMarginDp = 10)
        val btnRing = createM3Button(
            btnText = "Chamar",
            iconRes = R.drawable.ic_phone_call,
            bgColor = scheme.primary,
            contentColor = scheme.onPrimary,
            rippleColor = scheme.rippleOnPrimary,
            bottomMarginDp = 0
        ) {
            val name = callerEditText.text.toString().trim().ifEmpty { "Chamada teste" }
            bleManager.sendPhoneStatus(callState = BajajProtocol.CALL_INCOMING, caller = name)
            toast("Chamando: $name")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }
        val btnHangup = createM3Button(
            btnText = "Desligar",
            iconRes = R.drawable.ic_call_end,
            bgColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 0
        ) {
            bleManager.sendPhoneStatus(callState = BajajProtocol.CALL_NO_CALL, caller = "")
            toast("Chamada encerrada")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
        callBtnRow.addView(btnRing)
        callBtnRow.addView(btnHangup)
        callCard.addView(callBtnRow)

        val utilRow = createTwoColRow(bottomMarginDp = 0)
        val btnMissed = createM3Button(
            btnText = "Perdida",
            iconRes = R.drawable.ic_call_missed,
            bgColor = Color.TRANSPARENT,
            contentColor = scheme.error,
            rippleColor = scheme.rippleOnSurface,
            strokeColor = scheme.outline,
            bottomMarginDp = 0
        ) {
            val name = callerEditText.text.toString().trim().ifEmpty { "Chamada teste" }
            bleManager.sendMissedCall(name, "0000000000")
            toast("Chamada perdida enviada")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }
        val btnSync = createM3Button(
            btnText = "Sync status",
            iconRes = R.drawable.ic_sync,
            bgColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 0
        ) {
            bleManager.sendPhoneStatus()
            toast("Telemetria sincronizada")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
        utilRow.addView(btnMissed)
        utilRow.addView(btnSync)
        callCard.addView(utilRow)
        parent.addView(callCard)

        // ---------------------------------------------------------------------
        // SEÇÃO D: DIAGNÓSTICO AO VIVO
        // ---------------------------------------------------------------------
        addSectionHeader(parent, "Diagnóstico ao vivo")
        val diagBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        diagListenerText = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        }
        diagMapsText = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        }
        diagMusicText = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        }
        diagHeadsetVolText = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        }
        diagCallText = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        }
        diagAlertText = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        }
        diagControlsText = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(4))
        }
        diagBleText = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
        }
        diagBox.addView(diagListenerText)
        diagBox.addView(diagMapsText)
        diagBox.addView(diagMusicText)
        diagBox.addView(diagHeadsetVolText)
        diagBox.addView(diagCallText)
        diagBox.addView(diagAlertText)
        diagBox.addView(diagControlsText)
        diagBox.addView(diagBleText)
        parent.addView(diagBox)
    }

    private fun createM3SwitchRow(
        title: String,
        subtitle: String,
        iconRes: Int,
        checked: Boolean,
        contentDesc: String,
        onCheckedChanged: (Boolean) -> Unit
    ): Pair<LinearLayout, Switch> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = 0f,
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
        }
        val icon = M3Theme.makeTintedIcon(this, iconRes, 22, scheme.onSurfaceVariant)
        (icon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(14), 0)
        row.addView(icon)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(10), 0)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            M3Theme.applyType(this, 15f, bold = true, color = scheme.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            M3Theme.applyType(this, 13f, bold = false, color = scheme.onSurfaceVariant)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol)

        val sw = Switch(this).apply {
            isChecked = checked
            val trackChecked = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(scheme.primary)
                setSize(dp(52), dp(32))
            }
            val trackUnchecked = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(scheme.surfaceContainerHighest)
                setStroke(dp(2), scheme.outline)
                setSize(dp(52), dp(32))
            }
            trackDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), trackChecked)
                addState(intArrayOf(), trackUnchecked)
            }
            val thumbCheckedShape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(scheme.onPrimary)
                setSize(dp(24), dp(24))
            }
            val thumbUncheckedShape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(scheme.outline)
                setSize(dp(16), dp(16))
            }
            thumbDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_checked),
                    android.graphics.drawable.InsetDrawable(thumbCheckedShape, dp(4))
                )
                addState(
                    intArrayOf(),
                    android.graphics.drawable.InsetDrawable(thumbUncheckedShape, dp(8))
                )
            }
            trackTintList = null
            thumbTintList = null
            contentDescription = contentDesc
            setOnCheckedChangeListener { _, isNowChecked ->
                onCheckedChanged(isNowChecked)
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row to sw
    }

    private fun openTextSettingDialog(
        title: String,
        subtitle: String,
        fieldHint: String,
        initialValue: String,
        neutralText: String,
        onSave: (String) -> Unit
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val fieldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            background = M3Theme.textFieldSurface(this@MainActivity, scheme, dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val edit = EditText(this).apply {
            setText(initialValue)
            setSelection(initialValue.length)
            hint = fieldHint
            setTextColor(scheme.onSurface)
            setHintTextColor(scheme.onSurfaceVariant)
            M3Theme.applyType(this, 15f, bold = false, color = scheme.onSurface)
            background = null
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        fieldRow.addView(edit)
        content.addView(fieldRow)

        val handle = createM3Dialog(
            title = title,
            subtitle = subtitle,
            content = content,
            neutralText = neutralText,
            onNeutral = { dlg ->
                onSave("")
                dlg.dismiss()
            },
            confirmText = "Salvar"
        ) { dlg ->
            onSave(edit.text?.toString()?.trim().orEmpty())
            dlg.dismiss()
        }
        handle.dialog.show()
    }

    private fun resolveMediaPackageDisplay(pkg: String): String {
        if (pkg.isBlank()) return "Automático (Spotify, YT Music, Deezer...)"
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info)?.toString()?.trim().orEmpty().ifBlank { pkg }
        } catch (_: Exception) {
            pkg
        }
    }

    private fun openPreferredMediaDialog() {
        val knownPlayers = listOf(
            "" to "Automático (sessão ativa / padrão)",
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "deezer.android.app" to "Deezer",
            "com.amazon.mp3" to "Amazon Music",
            "com.sec.android.app.music" to "Samsung Music",
            "com.apple.android.music" to "Apple Music",
            "com.soundcloud.android" to "SoundCloud"
        )
        var selectedPkg = preferences.getPreferredMediaPackage()

        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rowBadges = mutableListOf<Pair<String, Pair<LinearLayout, ImageView>>>()

        knownPlayers.forEachIndexed { idx, (pkg, label) ->
            if (idx > 0) {
                listCol.addView(View(this).apply {
                    setBackgroundColor(scheme.outlineVariant)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
            }
            val tv = TextView(this).apply {
                text = label
                M3Theme.applyType(this, 14f, bold = true, color = scheme.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            row.addView(tv)
            val check = M3Theme.makeTintedIcon(this, R.drawable.ic_check, 18, scheme.primary)
            row.addView(check)
            rowBadges.add(pkg to (row to check))
            row.setOnClickListener {
                selectedPkg = pkg
                for ((p, pair) in rowBadges) {
                    val isSel = p == selectedPkg
                    pair.first.background = M3Theme.interactiveSurface(
                        context = this,
                        bgColor = if (isSel) scheme.secondaryContainer else Color.TRANSPARENT,
                        radiusPx = dp(10).toFloat(),
                        rippleColor = scheme.rippleOnSurface,
                        focusStrokeColor = scheme.primary
                    )
                    pair.second.visibility = if (isSel) View.VISIBLE else View.INVISIBLE
                }
            }
            listCol.addView(row)
        }

        for ((p, pair) in rowBadges) {
            val isSel = p == selectedPkg
            pair.first.background = M3Theme.interactiveSurface(
                context = this,
                bgColor = if (isSel) scheme.secondaryContainer else Color.TRANSPARENT,
                radiusPx = dp(10).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            pair.second.visibility = if (isSel) View.VISIBLE else View.INVISIBLE
        }

        val handle = createM3Dialog(
            title = "Player padrão de música",
            subtitle = "Aplicativo aberto ao acionar \"Abrir player\" pelo guidão quando nenhuma sessão estiver ativa.",
            content = listCol,
            confirmText = "Salvar"
        ) { dlg ->
            preferences.setPreferredMediaPackage(selectedPkg)
            refreshDiagnosticsUi()
            toast("Player padrão atualizado")
            dlg.dismiss()
        }
        handle.dialog.show()
    }

    private fun createRecommendedRow(
        title: String,
        iconRes: Int,
        iconTint: Int,
        onClick: () -> Unit
    ): Triple<LinearLayout, TextView, ImageView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = 0f,
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val icon = M3Theme.makeTintedIcon(this, iconRes, 22, iconTint)
        (icon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(14), 0)
        row.addView(icon)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        val titleTv = TextView(this).apply {
            text = title
            M3Theme.applyType(this, 15f, bold = true, color = scheme.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleTv)

        val statusTv = TextView(this).apply {
            text = "Verificando..."
            M3Theme.applyType(this, 13f, bold = false, color = scheme.onSurfaceVariant)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(statusTv)
        row.addView(textCol)

        val trailingIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_arrow_right, 18, scheme.onSurfaceVariant)
        row.addView(trailingIcon)
        return Triple(row, statusTv, trailingIcon)
    }

    // =========================================================================
    // DIÁLOGOS MATERIAL 3 (SELETOR DE APPS & RENOMEAR MOTO NO DISPOSITIVO)
    // =========================================================================
    private data class M3DialogHandle(
        val dialog: Dialog,
        val subtitleView: TextView?
    )

    private fun createM3Dialog(
        title: String,
        subtitle: String? = null,
        content: View,
        neutralText: String? = null,
        onNeutral: ((Dialog) -> Unit)? = null,
        cancelText: String = "Cancelar",
        confirmText: String = "Salvar",
        onConfirm: (Dialog) -> Unit
    ): M3DialogHandle {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val widthPx = minOf(resources.displayMetrics.widthPixels - dp(32), dp(520))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = M3Theme.roundedSurface(
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(M3Shape.EXTRA_LARGE_DP).toFloat()
            )
            elevation = dp(6).toFloat()
            clipToOutline = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isForceDarkAllowed = false
            }
            layoutParams = FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        val titleTv = TextView(this).apply {
            text = title
            M3Theme.applyType(this, 20f, bold = true, color = scheme.onSurface)
        }
        M3Theme.markAccessibilityHeading(titleTv)
        card.addView(titleTv)

        var subtitleTv: TextView? = null
        if (subtitle != null) {
            subtitleTv = TextView(this).apply {
                text = subtitle
                M3Theme.applyType(this, 13f, bold = false, color = scheme.onSurfaceVariant)
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(subtitleTv)
        }

        val contentWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(16), 0, dp(16)) }
        }
        contentWrapper.addView(content)
        card.addView(contentWrapper)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        if (neutralText != null && onNeutral != null) {
            val neutralBtn = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = M3Theme.interactiveSurface(
                    context = this@MainActivity,
                    bgColor = Color.TRANSPARENT,
                    radiusPx = dp(999).toFloat(),
                    rippleColor = scheme.rippleOnSurface,
                    focusStrokeColor = scheme.primary
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onNeutral(dialog) }
            }
            neutralBtn.addView(TextView(this).apply {
                text = neutralText
                M3Theme.applyType(this, 13f, bold = true, color = scheme.error)
                maxLines = 1
            })
            actionsRow.addView(neutralBtn)

            actionsRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f)
            })
        }

        val cancelBtn = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(999).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(8), 0) }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        cancelBtn.addView(TextView(this).apply {
            text = cancelText
            M3Theme.applyType(this, 14f, bold = true, color = scheme.primary)
            maxLines = 1
        })
        actionsRow.addView(cancelBtn)

        val confirmBtn = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.primary,
                radiusPx = dp(999).toFloat(),
                rippleColor = scheme.rippleOnPrimary,
                focusStrokeColor = scheme.primaryContainer
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onConfirm(dialog) }
        }
        confirmBtn.addView(TextView(this).apply {
            text = confirmText
            M3Theme.applyType(this, 14f, bold = true, color = scheme.onPrimary)
            maxLines = 1
        })
        actionsRow.addView(confirmBtn)
        card.addView(actionsRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.50f)
            setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        return M3DialogHandle(dialog, subtitleTv)
    }

    private fun openRenameDeviceDialog(address: String, originalBleName: String) {
        val currentCustom = preferences.getCustomDeviceName(address)
        val initialText = currentCustom ?: originalBleName

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val fieldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            background = M3Theme.textFieldSurface(this@MainActivity, scheme, dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val edit = EditText(this).apply {
            setText(initialText)
            setSelection(initialText.length)
            hint = "Nome exibido no aplicativo"
            setTextColor(scheme.onSurface)
            setHintTextColor(scheme.onSurfaceVariant)
            M3Theme.applyType(this, 15f, bold = false, color = scheme.onSurface)
            background = null
            setPadding(dp(14), dp(10), dp(4), dp(10))
            maxLines = 1
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        fieldRow.addView(edit)

        val clearBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(24).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Limpar nome"
            visibility = if (initialText.isNotEmpty()) View.VISIBLE else View.GONE
            setOnClickListener {
                edit.setText("")
                edit.requestFocus()
            }
        }
        val clearIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_call_end, 18, scheme.onSurfaceVariant)
        clearIcon.layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER }
        clearBtn.addView(clearIcon)
        fieldRow.addView(clearBtn)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearBtn.visibility = if (!s.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        content.addView(fieldRow)

        content.addView(TextView(this).apply {
            text = "Código do aparelho: $address"
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
        })

        val handle = createM3Dialog(
            title = "Alterar nome da moto",
            subtitle = "Atualiza o nome no aplicativo e tenta alterar também no Bluetooth do celular ($originalBleName).",
            content = content,
            neutralText = if (currentCustom != null) "Restaurar" else null,
            onNeutral = if (currentCustom != null) { dlg: Dialog ->
                preferences.setCustomDeviceName(address, "")
                bleManager.refreshDisplayNames()
                refreshPinnedBikeUi()
                refreshDeviceListUi()
                refreshMaintenanceUi(maintenanceManager.getSnapshot())
                val aliasRes = bleManager.tryApplySystemBluetoothAlias(address, "")
                when (aliasRes) {
                    SystemAliasResult.CLEARED -> toast("Nome original restaurado no app e no Bluetooth ($originalBleName)")
                    SystemAliasResult.NEEDS_ASSOCIATION -> {
                        pendingAliasAddress = address
                        pendingAliasOriginalName = originalBleName
                        pendingAliasValue = ""
                        bleManager.requestCompanionAssociation(
                            activity = this,
                            address = address,
                            onIntentSenderReady = { sender ->
                                try {
                                    startIntentSenderForResult(sender, REQ_COMPANION_ALIAS, null, 0, 0, 0)
                                } catch (_: Exception) {
                                    pendingAliasAddress = null
                                    toast("Nome restaurado no app ($originalBleName)")
                                }
                            },
                            onAlreadyAssociated = {
                                val retry = bleManager.tryApplySystemBluetoothAlias(address, "")
                                pendingAliasAddress = null
                                if (retry == SystemAliasResult.CLEARED) {
                                    toast("Nome original restaurado no app e no Bluetooth ($originalBleName)")
                                } else {
                                    toast("Nome restaurado no app ($originalBleName)")
                                }
                            },
                            onError = {
                                pendingAliasAddress = null
                                toast("Nome restaurado no app ($originalBleName)")
                            }
                        )
                    }
                    else -> toast("Nome original restaurado no app ($originalBleName)")
                }
                dlg.dismiss()
            } else null,
            confirmText = "Salvar"
        ) { dlg ->
            val typed = edit.text?.toString()?.trim().orEmpty()
            preferences.setCustomDeviceName(address, typed)
            bleManager.refreshDisplayNames()
            refreshPinnedBikeUi()
            refreshDeviceListUi()
            refreshMaintenanceUi(maintenanceManager.getSnapshot())
            val shown = preferences.getDisplayName(address, originalBleName)
            val aliasRes = bleManager.tryApplySystemBluetoothAlias(address, typed)
            when (aliasRes) {
                SystemAliasResult.APPLIED ->
                    toast("Nome salvo no app e no Bluetooth do celular: $shown")
                SystemAliasResult.CLEARED ->
                    toast("Nome restaurado no app e no Bluetooth: $shown")
                SystemAliasResult.NEEDS_ASSOCIATION -> {
                    pendingAliasAddress = address
                    pendingAliasOriginalName = originalBleName
                    pendingAliasValue = typed
                    bleManager.requestCompanionAssociation(
                        activity = this,
                        address = address,
                        onIntentSenderReady = { sender ->
                            try {
                                startIntentSenderForResult(sender, REQ_COMPANION_ALIAS, null, 0, 0, 0)
                            } catch (_: Exception) {
                                pendingAliasAddress = null
                                toast("Nome salvo no aplicativo: $shown")
                            }
                        },
                        onAlreadyAssociated = {
                            val retry = bleManager.tryApplySystemBluetoothAlias(address, typed)
                            pendingAliasAddress = null
                            if (retry == SystemAliasResult.APPLIED || retry == SystemAliasResult.CLEARED) {
                                toast("Nome salvo no app e no Bluetooth do celular: $shown")
                            } else {
                                toast("Nome salvo no aplicativo: $shown")
                            }
                        },
                        onError = { reason ->
                            pendingAliasAddress = null
                            toast("Salvo no app ($shown). Bluetooth do celular: $reason")
                        }
                    )
                }
                SystemAliasResult.UNSUPPORTED ->
                    toast("Salvo no app ($shown). Renomear no Bluetooth requer Android 11+.")
                else ->
                    toast("Nome salvo neste aplicativo: $shown")
            }
            dlg.dismiss()
        }
        handle.dialog.show()
    }

    private data class InstalledAppEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable?
    )

    private fun openInstalledAppsSelectorDialog() {
        val currentSelection = preferences.getSelectedNotificationPackages().toMutableSet()

        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Campo de busca Outlined M3 com ícone leading e botão limpar
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            background = M3Theme.textFieldSurface(this@MainActivity, scheme, dp(14).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
        val searchIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_search, 18, scheme.onSurfaceVariant)
        (searchIcon.layoutParams as LinearLayout.LayoutParams).setMargins(dp(14), 0, dp(8), 0)
        searchRow.addView(searchIcon)

        val searchBox = EditText(this).apply {
            hint = "Buscar aplicativo..."
            setTextColor(scheme.onSurface)
            setHintTextColor(scheme.onSurfaceVariant)
            M3Theme.applyType(this, 15f, bold = false, color = scheme.onSurface)
            background = null
            setPadding(0, dp(10), dp(4), dp(10))
            maxLines = 1
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        searchRow.addView(searchBox)

        val clearSearchBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(24).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Limpar busca"
            visibility = View.GONE
            setOnClickListener {
                searchBox.setText("")
                searchBox.requestFocus()
            }
        }
        val clearSearchIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_call_end, 16, scheme.onSurfaceVariant)
        clearSearchIcon.layoutParams = FrameLayout.LayoutParams(dp(16), dp(16)).apply { gravity = Gravity.CENTER }
        clearSearchBtn.addView(clearSearchIcon)
        searchRow.addView(clearSearchBtn)
        dialogContent.addView(searchRow)

        // Chips compactos M3 (altura visual 34dp dentro de alvo de toque 48dp)
        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetScroll.addView(presetRow)
        dialogContent.addView(presetScroll)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            )
        }
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val loadingTv = TextView(this).apply {
            text = "Carregando aplicativos instalados..."
            M3Theme.applyType(this, 14f, bold = false, color = scheme.onSurfaceVariant)
            setPadding(dp(4), dp(16), dp(4), dp(16))
        }
        listContainer.addView(loadingTv)
        scroll.addView(listContainer)
        dialogContent.addView(scroll)

        var subtitleRef: TextView? = null
        val updateSubtitleCount = {
            subtitleRef?.text = "${currentSelection.size} app(s) selecionado(s) para envio ao painel"
        }

        val handle = createM3Dialog(
            title = "Selecionar apps para notificação",
            subtitle = "${currentSelection.size} app(s) selecionado(s) para envio ao painel",
            content = dialogContent,
            confirmText = "Salvar"
        ) { dlg ->
            preferences.setSelectedNotificationPackages(currentSelection)
            refreshSelectedAppsSummary()
            toast("${currentSelection.size} app(s) salvos")
            dlg.dismiss()
        }
        subtitleRef = handle.subtitleView

        var allApps: List<InstalledAppEntry> = emptyList()

        val createM3CheckboxVisual = { checked: Boolean ->
            val box = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER }
            }
            val checkIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_check, 14, scheme.onPrimary)
            checkIcon.layoutParams = FrameLayout.LayoutParams(dp(14), dp(14)).apply { gravity = Gravity.CENTER }
            box.addView(checkIcon)

            val applyState: (Boolean) -> Unit = { isChecked ->
                box.background = if (isChecked) {
                    M3Theme.roundedSurface(scheme.primary, dp(4).toFloat())
                } else {
                    M3Theme.roundedSurface(
                        bgColor = Color.TRANSPARENT,
                        radiusPx = dp(4).toFloat(),
                        strokeColor = scheme.onSurfaceVariant,
                        strokeWidthPx = dp(2)
                    )
                }
                checkIcon.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
            applyState(checked)
            Pair(box, applyState)
        }

        val renderApps: (String) -> Unit = { query ->
            listContainer.removeAllViews()
            val q = query.trim().lowercase(Locale.getDefault())
            val filtered = allApps.filter {
                q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q) ||
                    it.packageName.lowercase(Locale.getDefault()).contains(q)
            }.sortedWith(
                compareByDescending<InstalledAppEntry> { currentSelection.contains(it.packageName) }
                    .thenBy { it.label.lowercase(Locale.getDefault()) }
            )

            if (filtered.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "Nenhum aplicativo encontrado."
                    M3Theme.applyType(this, 14f, bold = false, color = scheme.onSurfaceVariant)
                    setPadding(dp(4), dp(16), dp(4), dp(16))
                }
                listContainer.addView(emptyTv)
            } else {
                filtered.forEachIndexed { index, entry ->
                    if (index > 0) {
                        listContainer.addView(View(this).apply {
                            setBackgroundColor(scheme.outlineVariant)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                dp(1)
                            )
                        })
                    }

                    var isSelected = currentSelection.contains(entry.packageName)

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(56)
                        setPadding(dp(4), dp(8), dp(2), dp(8))
                        background = M3Theme.interactiveSurface(
                            context = this@MainActivity,
                            bgColor = Color.TRANSPARENT,
                            radiusPx = dp(12).toFloat(),
                            rippleColor = scheme.rippleOnSurface,
                            focusStrokeColor = scheme.primary
                        )
                        isClickable = true
                        isFocusable = true
                    }

                    val iconView = ImageView(this).apply {
                        if (entry.icon != null) setImageDrawable(entry.icon)
                        else setImageResource(R.drawable.ic_apps)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                            setMargins(0, 0, dp(12), 0)
                        }
                    }
                    row.addView(iconView)

                    val col = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                            setMargins(0, 0, dp(8), 0)
                        }
                    }
                    col.addView(TextView(this).apply {
                        text = entry.label
                        M3Theme.applyType(this, 14f, bold = true, color = scheme.onSurface)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    col.addView(TextView(this).apply {
                        text = entry.packageName
                        M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    row.addView(col)

                    val (checkboxBox, applyCheckState) = createM3CheckboxVisual(isSelected)
                    val checkTouchWrapper = FrameLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    }
                    checkTouchWrapper.addView(checkboxBox)
                    row.addView(checkTouchWrapper)

                    val updateRowAccessibility = {
                        row.contentDescription = "${entry.label}, ${if (isSelected) "selecionado" else "não selecionado"}"
                    }
                    updateRowAccessibility()

                    row.setOnClickListener {
                        isSelected = !isSelected
                        if (isSelected) currentSelection.add(entry.packageName)
                        else currentSelection.remove(entry.packageName)
                        applyCheckState(isSelected)
                        updateRowAccessibility()
                        updateSubtitleCount()
                    }
                    listContainer.addView(row)
                }
            }
        }

        val addPresetChip = { title: String, iconRes: Int, emphasized: Boolean, onClick: () -> Unit ->
            val touchWrapper = FrameLayout(this).apply {
                minimumHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(48)
                ).apply { setMargins(0, 0, dp(8), 0) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onClick()
                    updateSubtitleCount()
                    renderApps(searchBox.text?.toString() ?: "")
                }
            }
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(34)
                setPadding(dp(12), dp(6), dp(14), dp(6))
                background = M3Theme.interactiveSurface(
                    context = this@MainActivity,
                    bgColor = if (emphasized) scheme.secondaryContainer else scheme.surfaceContainer,
                    radiusPx = dp(M3Shape.SMALL_DP).toFloat(),
                    rippleColor = scheme.rippleOnSurface,
                    focusStrokeColor = scheme.primary,
                    normalStrokeColor = if (emphasized) 0 else scheme.outlineVariant,
                    normalStrokeWidthPx = if (emphasized) 0 else dp(1)
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_VERTICAL }
            }
            val chipTint = if (emphasized) scheme.onSecondaryContainer else scheme.onSurfaceVariant
            val chipIcon = M3Theme.makeTintedIcon(this, iconRes, 16, chipTint)
            (chipIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(6), 0)
            pill.addView(chipIcon)
            pill.addView(TextView(this).apply {
                text = title
                M3Theme.applyType(
                    this,
                    13f,
                    bold = true,
                    color = if (emphasized) scheme.onSecondaryContainer else scheme.onSurface
                )
                maxLines = 1
            })
            touchWrapper.addView(pill)
            presetRow.addView(touchWrapper)
        }

        addPresetChip("Padrão msgs", R.drawable.ic_chat_message, true) {
            currentSelection.addAll(AppPreferences.DEFAULT_NOTIFICATION_PACKAGES)
        }
        addPresetChip("Desmarcar todos", R.drawable.ic_close, false) {
            currentSelection.clear()
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val str = s?.toString() ?: ""
                clearSearchBtn.visibility = if (str.isNotEmpty()) View.VISIBLE else View.GONE
                if (allApps.isNotEmpty()) {
                    renderApps(str)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        handle.dialog.show()

        Thread {
            val loaded = loadInstalledLauncherApps()
            runOnUiThread {
                allApps = loaded
                renderApps(searchBox.text?.toString() ?: "")
            }
        }.start()
    }

    /*
    // =========================================================================
    // [FUTURAS MELHORIAS: MAPA LIVRE ALTERNATIVO (OSM / LEAFLET / OSRM)]
    // Mantido comentado a pedido para futuras melhorias e implementações.
    // =========================================================================

    private fun resolveOsmManeuver(modifierOrType: String, counterClockwise: Boolean): Byte {
        val m = modifierOrType.lowercase(Locale.US).trim()
        return when {
            m.contains("sharp right") -> BajajProtocol.ARROW_SHARP_RIGHT_SOLID
            m.contains("slight right") -> BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID
            m.contains("right") -> BajajProtocol.ARROW_RIGHT_SOLID
            m.contains("sharp left") -> BajajProtocol.ARROW_SHARP_LEFT_SOLID
            m.contains("slight left") -> BajajProtocol.ARROW_SLIGHT_LEFT_SOLID
            m.contains("left") -> BajajProtocol.ARROW_LEFT_SOLID
            m.contains("uturn") || m.contains("u-turn") -> BajajProtocol.ARROW_UTURN_LEFT_SOLID
            m.contains("roundabout") || m.contains("rotary") -> {
                if (counterClockwise) BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID
                else BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID
            }
            m.contains("fork") -> BajajProtocol.ARROW_FORK_RIGHT_SOLID
            m.contains("merge") -> BajajProtocol.ARROW_MERGE
            m.contains("ramp") -> BajajProtocol.ARROW_RAMP_RIGHT_SOLID
            m.contains("arrive") || m.contains("destination") -> BajajProtocol.ARROW_DESTINATION_SOLID
            else -> BajajProtocol.ARROW_STRAIGHT_SOLID
        }
    }

    private fun maneuverCodeToDrawableRes(code: Byte): Int = when (code) {
        BajajProtocol.ARROW_LEFT_SOLID -> R.drawable.ic_arrow_left
        BajajProtocol.ARROW_RIGHT_SOLID -> R.drawable.ic_arrow_right
        BajajProtocol.ARROW_SLIGHT_LEFT_SOLID -> R.drawable.ic_arrow_slight_left
        BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID -> R.drawable.ic_arrow_slight_right
        BajajProtocol.ARROW_SHARP_LEFT_SOLID -> R.drawable.ic_arrow_sharp_left
        BajajProtocol.ARROW_SHARP_RIGHT_SOLID -> R.drawable.ic_arrow_sharp_right
        BajajProtocol.ARROW_UTURN_LEFT_SOLID, BajajProtocol.ARROW_UTURN_RIGHT_SOLID -> R.drawable.ic_arrow_uturn
        BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID, BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID -> R.drawable.ic_arrow_roundabout
        BajajProtocol.ARROW_FORK_LEFT_SOLID -> R.drawable.ic_arrow_fork_left
        BajajProtocol.ARROW_FORK_RIGHT_SOLID -> R.drawable.ic_arrow_fork_right
        BajajProtocol.ARROW_RAMP_LEFT_SOLID -> R.drawable.ic_arrow_ramp_left
        BajajProtocol.ARROW_RAMP_RIGHT_SOLID -> R.drawable.ic_arrow_ramp_right
        BajajProtocol.ARROW_MERGE -> R.drawable.ic_arrow_merge
        BajajProtocol.ARROW_DESTINATION_SOLID -> R.drawable.ic_flag_destination
        else -> R.drawable.ic_arrow_straight
    }

    private inner class OsmMapJsBridge(
        private val onRouteReady: (Int, Int, Int, String, String, Int) -> Unit,
        private val onRouteReset: () -> Unit
    ) {
        @JavascriptInterface
        fun onRouteCalculated(totalM: Int, durationSec: Int, stepDist: Int, modifier: String, street: String, exit: Int) {
            runOnUiThread {
                onRouteReady(totalM, durationSec, stepDist, modifier, street, exit)
            }
        }

        @JavascriptInterface
        fun onRouteCleared() {
            runOnUiThread {
                onRouteReset()
            }
        }

        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread { toast(msg) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openFreeCommercialMapDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // --- LAYER 0: MAP VIEW (FULLSCREEN) ---
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        rootLayout.addView(webView)

        // Navigation state
        var cachedStepDist = 0
        var cachedArrowCode: Byte = BajajProtocol.ARROW_STRAIGHT_SOLID
        var cachedStreet = ""
        var cachedTotalDist = 0
        var cachedEtaSec = -1
        var cachedExit = 0
        var isNavActive = false

        // --- LAYER 1: TOP FLOATING BAR (Search + Chips) ---
        val topFloatingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                val statusBarInset = dp(48)
                setMargins(dp(16), statusBarInset, dp(16), 0)
            }
        }

        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = M3Theme.roundedSurface(
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(26).toFloat()
            )
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val backBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(20).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Fechar mapa"
            setOnClickListener { dialog.dismiss() }
        }
        val backIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_close, 20, scheme.onSurface)
        backIcon.layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER }
        backBtn.addView(backIcon)
        searchPill.addView(backBtn)

        val searchInput = EditText(this).apply {
            hint = "Para onde vamos? (ex: Posto, rua...)"
            setTextColor(scheme.onSurface)
            setHintTextColor(scheme.onSurfaceVariant)
            background = null
            M3Theme.applyType(this, 15f, bold = false, color = scheme.onSurface)
            setPadding(dp(10), dp(8), dp(6), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        searchPill.addView(searchInput)

        val clearInputBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(18).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            contentDescription = "Limpar busca"
            setOnClickListener {
                searchInput.setText("")
                visibility = View.GONE
            }
        }
        val clearIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_close, 16, scheme.outline)
        clearIcon.layoutParams = FrameLayout.LayoutParams(dp(16), dp(16)).apply { gravity = Gravity.CENTER }
        clearInputBtn.addView(clearIcon)
        searchPill.addView(clearInputBtn)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearInputBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val searchBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.primary,
                radiusPx = dp(20).toFloat(),
                rippleColor = scheme.rippleOnPrimary,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Buscar destino"
        }
        val searchIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_search, 18, scheme.onPrimary)
        searchIcon.layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER }
        searchBtn.addView(searchIcon)
        searchPill.addView(searchBtn)
        topFloatingContainer.addView(searchPill)

        // Chips row
        val chipsScroll = HorizontalScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
        }
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val addMapChip = { label: String, query: String ->
            val chip = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = M3Theme.interactiveSurface(
                    context = this@MainActivity,
                    bgColor = scheme.surfaceContainerHigh,
                    radiusPx = dp(999).toFloat(),
                    rippleColor = scheme.rippleOnSurface,
                    focusStrokeColor = scheme.primary
                )
                elevation = dp(3).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                isClickable = true
                isFocusable = true
            }
            chip.addView(TextView(this).apply {
                text = label
                M3Theme.applyType(this, 13f, bold = true, color = scheme.onSurface)
                maxLines = 1
            })
            chip.setOnClickListener {
                searchInput.setText(query)
                searchBtn.performClick()
            }
            chipsRow.addView(chip)
        }
        addMapChip("⛽ Posto", "Posto de combustível")
        addMapChip("🔧 Oficina", "Oficina moto")
        addMapChip("🍽️ Restaurante", "Restaurante")
        addMapChip("🏪 Farmácia", "Farmácia")
        addMapChip("🅿️ Estacionamento", "Estacionamento")

        val addActionChip = { label: String, onClick: () -> Unit ->
            val chip = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = M3Theme.interactiveSurface(
                    context = this@MainActivity,
                    bgColor = scheme.surfaceContainerHigh,
                    radiusPx = dp(999).toFloat(),
                    rippleColor = scheme.rippleOnSurface,
                    focusStrokeColor = scheme.primary
                )
                elevation = dp(3).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            chip.addView(TextView(this).apply {
                text = label
                M3Theme.applyType(this, 13f, bold = true, color = scheme.primary)
                maxLines = 1
            })
            chipsRow.addView(chip)
        }
        addActionChip("🛰️ Satélite") {
            webView.evaluateJavascript("switchLayer('satellite');", null)
        }
        addActionChip("🗺️ Google") {
            webView.evaluateJavascript("switchLayer('google');", null)
        }
        addActionChip("🌍 Esri") {
            webView.evaluateJavascript("switchLayer('esri');", null)
        }
        chipsScroll.addView(chipsRow)
        topFloatingContainer.addView(chipsScroll)
        rootLayout.addView(topFloatingContainer)

        // --- LAYER 2: FLOATING GPS RECENTER FAB ---
        val recenterFab = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(16), dp(190))
            }
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(24).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            elevation = dp(6).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Recentralizar minha posição"
            setOnClickListener {
                webView.evaluateJavascript("recenterMap();", null)
            }
        }
        val recenterIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_location_pin, 22, scheme.primary)
        recenterIcon.layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER }
        recenterFab.addView(recenterIcon)
        rootLayout.addView(recenterFab)

        // --- LAYER 3: BOTTOM FLOATING NAVIGATION CARD ---
        val bottomNavCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = M3Theme.roundedSurface(
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(20).toFloat()
            )
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(16), 0, dp(16), dp(20))
            }
        }

        // Idle state inside bottom card
        val idleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val idleIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_location_pin, 20, scheme.primary)
        (idleIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(10), 0)
        idleLayout.addView(idleIcon)

        val idleText = TextView(this).apply {
            text = "Toque no mapa ou use a busca para traçar sua rota"
            M3Theme.applyRole(this, M3TypeRole.BODY_MEDIUM, scheme.onSurfaceVariant)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        idleLayout.addView(idleText)
        bottomNavCard.addView(idleLayout)

        // Active route state inside bottom card
        val activeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val maneuverHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }

        val maneuverIconContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            background = M3Theme.roundedSurface(scheme.primaryContainer, dp(14).toFloat())
        }
        val maneuverIconView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER }
            setImageResource(R.drawable.ic_arrow_straight)
            setColorFilter(scheme.onPrimaryContainer, PorterDuff.Mode.SRC_IN)
        }
        maneuverIconContainer.addView(maneuverIconView)
        maneuverHeaderRow.addView(maneuverIconContainer)

        val maneuverInfoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(dp(12), 0, 0, 0)
            }
        }
        val stepTitleTv = TextView(this).apply {
            text = "Próxima manobra"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tripSummaryTv = TextView(this).apply {
            text = "Calculando rota..."
            M3Theme.applyRole(this, M3TypeRole.BODY_SMALL, scheme.onSurfaceVariant)
            maxLines = 1
        }
        maneuverInfoCol.addView(stepTitleTv)
        maneuverInfoCol.addView(tripSummaryTv)
        maneuverHeaderRow.addView(maneuverInfoCol)
        activeLayout.addView(maneuverHeaderRow)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        var btnStartNav: LinearLayout? = null
        btnStartNav = createM3Button(
            btnText = "Navegar no Painel",
            iconRes = R.drawable.ic_two_wheeler,
            bgColor = scheme.primary,
            contentColor = scheme.onPrimary,
            rippleColor = scheme.rippleOnPrimary,
            bottomMarginDp = 0
        ) {
            if (cachedTotalDist <= 0) {
                toast("Selecione um destino no mapa primeiro")
                return@createM3Button
            }
            isNavActive = true
            webView.evaluateJavascript("setNavigating(true);", null)
            bleManager.sendNavigation(
                distanceMeters = cachedStepDist,
                maneuverCode = cachedArrowCode,
                text = cachedStreet.ifBlank { "OpenStreetMap" },
                totalDistanceMeters = cachedTotalDist,
                roundaboutExit = cachedExit,
                etaSeconds = cachedEtaSec,
                sourceLabel = "OpenStreetMap"
            )
            toast("Navegação transmitida para o painel Bajaj!")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }

        val btnStopNav = createM3Button(
            btnText = "Encerrar",
            iconRes = R.drawable.ic_call_end,
            bgColor = Color.TRANSPARENT,
            contentColor = scheme.error,
            rippleColor = scheme.rippleOnSurface,
            strokeColor = scheme.outline,
            bottomMarginDp = 0
        ) {
            isNavActive = false
            bleManager.stopNavigation()
            webView.evaluateJavascript("clearRoute();", null)
            activeLayout.visibility = View.GONE
            idleLayout.visibility = View.VISIBLE
            toast("Navegação encerrada")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.75f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }

        actionsRow.addView(btnStartNav)
        actionsRow.addView(btnStopNav)
        activeLayout.addView(actionsRow)
        bottomNavCard.addView(activeLayout)
        rootLayout.addView(bottomNavCard)

        // Setup JS Bridge
        val jsBridge = OsmMapJsBridge(
            onRouteReady = { totalM, durationSec, stepDist, modifier, street, exit ->
                val distStr = if (totalM >= 1000) String.format(Locale.US, "%.1f km", totalM / 1000.0) else "$totalM m"
                val min = (durationSec + 59) / 60
                val streetLabel = if (street.isNotBlank()) street else "Próxima via"
                val stepDistStr = if (stepDist >= 1000) String.format(Locale.US, "%.1f km", stepDist / 1000.0) else "$stepDist m"

                stepTitleTv.text = "Em $stepDistStr: $streetLabel"
                tripSummaryTv.text = "$distStr ($min min restantes)"

                val arrowCode = resolveOsmManeuver(modifier, preferences.isRoundaboutCounterClockwise())
                val clusterArrow = if (stepDist > 3000 && arrowCode != BajajProtocol.ARROW_DESTINATION_SOLID) {
                    BajajProtocol.ARROW_STRAIGHT_SOLID
                } else {
                    arrowCode
                }

                maneuverIconView.setImageResource(maneuverCodeToDrawableRes(clusterArrow))

                cachedStepDist = stepDist
                cachedArrowCode = clusterArrow
                cachedStreet = streetLabel
                cachedTotalDist = totalM
                cachedEtaSec = durationSec
                cachedExit = if (exit > 0) GoogleMapsNotificationParser.mapOrdinalExitToClusterSpoke(exit) else 0

                idleLayout.visibility = View.GONE
                activeLayout.visibility = View.VISIBLE

                if (isNavActive) {
                    bleManager.sendNavigation(
                        distanceMeters = cachedStepDist,
                        maneuverCode = cachedArrowCode,
                        text = cachedStreet,
                        totalDistanceMeters = cachedTotalDist,
                        roundaboutExit = cachedExit,
                        etaSeconds = cachedEtaSec,
                        sourceLabel = "OpenStreetMap"
                    )
                }
            },
            onRouteReset = {
                cachedTotalDist = 0
                cachedStepDist = 0
                isNavActive = false
                activeLayout.visibility = View.GONE
                idleLayout.visibility = View.VISIBLE
            }
        )
        webView.addJavascriptInterface(jsBridge, "AndroidNav")

        // Search action
        val triggerSearch = {
            val q = searchInput.text.toString().trim()
            if (q.isNotEmpty()) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
                val escaped = q.replace("'", "\\'")
                webView.evaluateJavascript("searchAddress('$escaped');", null)
            }
        }
        searchBtn.setOnClickListener { triggerSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch()
                true
            } else false
        }

        // Location acquisition
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        val mmLoc = try {
            maintenanceManager.getLastLocation()
        } catch (_: Exception) {
            null
        }
        val lastFix = mmLoc ?: try {
            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } catch (_: SecurityException) {
            null
        }

        val hasInitFix = (lastFix != null)
        val initLat = lastFix?.latitude ?: -14.2350
        val initLng = lastFix?.longitude ?: -51.9253

        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              <style>
                body, html, #map { margin: 0; padding: 0; height: 100%; width: 100%; background: #121212; font-family: sans-serif; }
                .leaflet-control-attribution { font-size: 9px !important; background: rgba(0,0,0,0.6) !important; color: #AAA !important; border-radius: 4px; }
                .leaflet-control-attribution a { color: #80D8FF !important; }
                .leaflet-popup-content-wrapper { border-radius: 14px; background: #212121; color: #FFF; box-shadow: 0 4px 16px rgba(0,0,0,0.5); }
                .leaflet-popup-tip { background: #212121; }
                .leaflet-popup-content { margin: 10px 14px; font-size: 13px; line-height: 1.4; }
                .user-puck {
                  position: relative;
                  width: 18px; height: 18px;
                  background: #00E5FF;
                  border: 3px solid #FFFFFF;
                  border-radius: 50%;
                  box-shadow: 0 0 10px rgba(0, 229, 255, 0.8), 0 2px 6px rgba(0,0,0,0.5);
                }
                .user-ring {
                  position: absolute;
                  top: -7px; left: -7px;
                  width: 32px; height: 32px;
                  border-radius: 50%;
                  background: rgba(0, 229, 255, 0.25);
                  animation: pulse 2s infinite ease-out;
                }
                @keyframes pulse {
                  0% { transform: scale(0.5); opacity: 1; }
                  100% { transform: scale(1.6); opacity: 0; }
                }
                .dest-pin-box {
                  width: 24px; height: 24px;
                  background: #FF3D00;
                  border: 2px solid #FFFFFF;
                  border-radius: 50% 50% 50% 0;
                  transform: rotate(-45deg);
                  box-shadow: 0 3px 8px rgba(0,0,0,0.5);
                }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <script>
                var hasGpsLock = $hasInitFix;
                var userLat = hasGpsLock ? $initLat : null;
                var userLng = hasGpsLock ? $initLng : null;
                var destLat = null;
                var destLng = null;
                var isNavigating = false;

                var startViewLat = hasGpsLock ? userLat : -14.2350;
                var startViewLng = hasGpsLock ? userLng : -51.9253;
                var startZoom = hasGpsLock ? 16 : 4;

                var map = L.map('map', { zoomControl: false, attributionControl: false }).setView([startViewLat, startViewLng], startZoom);

                // Google Streets - 100% free, fast, familiar, zero watermarks, zero API keys
                var googleStreets = L.tileLayer('https://{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}', {
                  maxZoom: 20,
                  subdomains: ['mt0', 'mt1', 'mt2', 'mt3']
                });

                // Google Satellite + Street labels (Hybrid)
                var googleHybrid = L.tileLayer('https://{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
                  maxZoom: 20,
                  subdomains: ['mt0', 'mt1', 'mt2', 'mt3']
                });

                // Esri World Street Map - reliable commercial alternative
                var esriStreets = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}', {
                  maxZoom: 19
                });

                var currentBaseLayer = googleStreets;
                currentBaseLayer.addTo(map);

                function switchLayer(type) {
                  if (currentBaseLayer) map.removeLayer(currentBaseLayer);
                  if (type === 'satellite') {
                    currentBaseLayer = googleHybrid;
                  } else if (type === 'esri') {
                    currentBaseLayer = esriStreets;
                  } else {
                    currentBaseLayer = googleStreets;
                  }
                  currentBaseLayer.addTo(map);
                }

                var userIcon = L.divIcon({
                  className: 'user-icon-container',
                  html: '<div class="user-puck"><div class="user-ring"></div></div>',
                  iconSize: [18, 18],
                  iconAnchor: [9, 9]
                });

                var destIcon = L.divIcon({
                  className: 'dest-icon-container',
                  html: '<div class="dest-pin-box"></div>',
                  iconSize: [24, 24],
                  iconAnchor: [12, 24],
                  popupAnchor: [0, -26]
                });

                var userMarker = null;
                if (hasGpsLock) {
                  userMarker = L.marker([userLat, userLng], { icon: userIcon }).addTo(map);
                }

                var destMarker = null;
                var routeBacking = null;
                var routePolyline = null;

                function setUserLocation(lat, lng) {
                  var isFirst = !hasGpsLock;
                  hasGpsLock = true;
                  userLat = lat;
                  userLng = lng;

                  if (!userMarker) {
                    userMarker = L.marker([lat, lng], { icon: userIcon }).addTo(map);
                  } else {
                    userMarker.setLatLng([lat, lng]);
                  }

                  if (isFirst) {
                    map.setView([lat, lng], 16, { animate: true });
                    if (destLat !== null && destLng !== null) {
                      calculateRoute(userLat, userLng, destLat, destLng);
                    }
                  } else if (isNavigating && destLat !== null && destLng !== null) {
                    calculateRoute(userLat, userLng, destLat, destLng);
                  }
                }

                function recenterMap() {
                  if (hasGpsLock && userLat !== null && userLng !== null) {
                    map.setView([userLat, userLng], 16, { animate: true });
                  } else {
                    if (window.AndroidNav) window.AndroidNav.showToast('Aguardando sinal GPS...');
                  }
                }

                function searchAddress(query) {
                  var url = 'https://nominatim.openstreetmap.org/search?format=json&limit=5&q=' + encodeURIComponent(query);
                  if (hasGpsLock && userLat !== null && userLng !== null) {
                    var delta = 0.35;
                    var minLon = userLng - delta;
                    var maxLon = userLng + delta;
                    var minLat = userLat - delta;
                    var maxLat = userLat + delta;
                    url += '&viewbox=' + minLon + ',' + maxLat + ',' + maxLon + ',' + minLat + '&bounded=0';
                  }

                  fetch(url, { headers: { 'Accept-Language': 'pt-BR,pt;q=0.9,en;q=0.8' } })
                    .then(function(r){ return r.json(); })
                    .then(function(data){
                      if (data && data.length > 0) {
                        var best = data[0];
                        var lat = parseFloat(best.lat);
                        var lon = parseFloat(best.lon);
                        var title = best.display_name.split(',')[0];
                        setDestination(lat, lon, title || query);
                      } else {
                        if (window.AndroidNav) window.AndroidNav.showToast('Nenhum local encontrado por perto');
                      }
                    }).catch(function(e){
                      if (window.AndroidNav) window.AndroidNav.showToast('Erro ao buscar local');
                    });
                }

                function setDestination(lat, lng, name) {
                  destLat = lat;
                  destLng = lng;
                  if (destMarker) map.removeLayer(destMarker);
                  destMarker = L.marker([lat, lng], { icon: destIcon }).addTo(map);
                  if (name) destMarker.bindPopup('<b>' + name + '</b>').openPopup();

                  if (hasGpsLock && userLat !== null && userLng !== null) {
                    calculateRoute(userLat, userLng, lat, lng);
                  } else {
                    map.setView([lat, lng], 15, { animate: true });
                    if (window.AndroidNav) window.AndroidNav.showToast('Destino marcado. Aguardando sinal GPS...');
                  }
                }

                map.on('click', function(e) {
                  setDestination(e.latlng.lat, e.latlng.lng, 'Destino selecionado');
                });

                function calculateRoute(lat1, lon1, lat2, lon2) {
                  var url = 'https://router.project-osrm.org/route/v1/driving/' + lon1 + ',' + lat1 + ';' + lon2 + ',' + lat2 + '?overview=full&geometries=geojson&steps=true';
                  fetch(url)
                    .then(function(r){ return r.json(); })
                    .then(function(data){
                      if (data.code === 'Ok' && data.routes && data.routes.length > 0) {
                        var route = data.routes[0];
                        if (routeBacking) map.removeLayer(routeBacking);
                        if (routePolyline) map.removeLayer(routePolyline);

                        var coords = route.geometry.coordinates.map(function(c){ return [c[1], c[0]]; });
                        routeBacking = L.polyline(coords, { color: '#005088', weight: 11, opacity: 0.6, lineJoin: 'round' }).addTo(map);
                        routePolyline = L.polyline(coords, { color: '#00E5FF', weight: 7, opacity: 0.95, lineJoin: 'round' }).addTo(map);

                        if (!isNavigating) {
                          map.fitBounds(routePolyline.getBounds(), { padding: [70, 70] });
                        }

                        var steps = (route.legs && route.legs[0] && route.legs[0].steps) ? route.legs[0].steps : [];
                        var totalM = Math.round(route.distance);
                        var durationSec = Math.round(route.duration);
                        var nextStep = (steps.length > 1) ? steps[1] : (steps[0] || {});
                        var nextDist = Math.round(nextStep.distance || 0);
                        var nextMod = (nextStep.maneuver && nextStep.maneuver.modifier) || (nextStep.maneuver && nextStep.maneuver.type) || 'straight';
                        var nextStreet = nextStep.name || 'Próxima via';
                        var nextExit = (nextStep.maneuver && nextStep.maneuver.exit) || 0;

                        if (window.AndroidNav) {
                          window.AndroidNav.onRouteCalculated(totalM, durationSec, nextDist, nextMod, nextStreet, nextExit);
                        }
                      } else {
                        if (window.AndroidNav) window.AndroidNav.showToast('Não foi possível traçar rota');
                      }
                    }).catch(function(e){
                      if (window.AndroidNav) window.AndroidNav.showToast('Falha ao obter rota OSRM');
                    });
                }

                function setNavigating(navigating) {
                  isNavigating = navigating;
                }

                function clearRoute() {
                  isNavigating = false;
                  if (destMarker) { map.removeLayer(destMarker); destMarker = null; }
                  if (routeBacking) { map.removeLayer(routeBacking); routeBacking = null; }
                  if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
                  destLat = null;
                  destLng = null;
                  if (hasGpsLock && userLat !== null && userLng !== null) {
                    map.setView([userLat, userLng], 16, { animate: true });
                  }
                  if (window.AndroidNav) window.AndroidNav.onRouteCleared();
                }
              </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://maps.google.com", htmlContent, "text/html", "UTF-8", null)

        // GPS live listener
        val locListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val lat = loc.latitude
                val lon = loc.longitude
                webView.evaluateJavascript("setUserLocation($lat, $lon);", null)
            }
            override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, locListener)
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, locListener)
        } catch (_: SecurityException) {}

        dialog.setOnDismissListener {
            try {
                lm?.removeUpdates(locListener)
            } catch (_: SecurityException) {}
            if (isNavActive) {
                bleManager.stopNavigation()
            }
            webView.destroy()
        }

        dialog.setContentView(rootLayout)
        val win: Window? = dialog.window
        if (win != null) {
            win.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                win.setDecorFitsSystemWindows(false)
            }
        }
        dialog.show()
    }
    */

    private fun loadInstalledLauncherApps(): List<InstalledAppEntry> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val map = LinkedHashMap<String, InstalledAppEntry>()
        for (ri in resolveList) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == packageName || pkg == "com.google.android.apps.maps") continue
            if (map.containsKey(pkg)) continue
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
            val icon = try {
                ri.loadIcon(pm)
            } catch (_: Exception) {
                null
            }
            map[pkg] = InstalledAppEntry(pkg, label, icon)
        }

        // Garante pacotes já salvos na lista mesmo se não tiverem ícone launcher
        for (savedPkg in preferences.getSelectedNotificationPackages()) {
            if (!map.containsKey(savedPkg)) {
                try {
                    val info = pm.getApplicationInfo(savedPkg, 0)
                    val label = pm.getApplicationLabel(info)?.toString()?.trim().orEmpty().ifBlank { savedPkg }
                    val icon = pm.getApplicationIcon(info)
                    map[savedPkg] = InstalledAppEntry(savedPkg, label, icon)
                } catch (_: Exception) {
                }
            }
        }
        return map.values.toList()
    }

    private fun refreshSelectedAppsSummary() {
        if (!::selectedAppsSummaryText.isInitialized) return
        val selected = preferences.getSelectedNotificationPackages()
        if (selected.isEmpty()) {
            selectedAppsSummaryText.text = "Nenhum app selecionado (toque para escolher)"
            return
        }
        val names = selected.take(3).map { pkg ->
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info)?.toString()?.trim().orEmpty().ifBlank { pkg }
            } catch (_: Exception) {
                pkg.substringAfterLast('.')
            }
        }
        val extra = if (selected.size > 3) " +${selected.size - 3}" else ""
        selectedAppsSummaryText.text = "${selected.size} ativos: ${names.joinToString(", ")}$extra"
    }

    private fun refreshAlertModeSelectionUi() {
        val current = preferences.getPanelAlertMode()
        for ((mode, views) in alertModeRows) {
            val selected = mode == current
            views.row.isSelected = selected
            views.row.background = M3Theme.interactiveSurface(
                context = this,
                bgColor = if (selected) scheme.secondaryContainer else Color.TRANSPARENT,
                radiusPx = 0f,
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            views.leadIcon.imageTintList = ColorStateList.valueOf(
                if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
            )
            views.label.setTextColor(if (selected) scheme.onSecondaryContainer else scheme.onSurface)
            views.desc.setTextColor(if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
            views.checkBadge.imageTintList = ColorStateList.valueOf(scheme.onSecondaryContainer)
            views.checkBadge.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun isIgnoringBatteryOptimizationsCompat(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = getSystemService(PowerManager::class.java)
            pm != null && pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasCallAndContactPermissions(): Boolean {
        val phoneOk = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val callLogOk = checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val contactsOk = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return phoneOk && callLogOk && contactsOk
    }

    private fun hasSmsAndCallControlPermissions(): Boolean {
        val answerOk = checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        val sendSmsOk = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val recvSmsOk = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        return answerOk && sendSmsOk && recvSmsOk
    }

    private fun refreshDiagnosticsUi() {
        val listenerEnabled = MapsNotificationListener.isEnabledInSettings(this)
        val listenerBound = bleManager.isNotificationListenerConnected()
        val autoStartOn = preferences.isAutoStartEnabled()
        val batteryIgnored = isIgnoringBatteryOptimizationsCompat()
        val callsAndContactsOk = hasCallAndContactPermissions()
        val smsAndControlsOk = hasSmsAndCallControlPermissions()

        if (::mapsListenerStatusText.isInitialized) {
            mapsListenerStatusText.text = when {
                listenerEnabled && listenerBound -> "Captura de notificações ativa"
                listenerEnabled -> "Permissão ativa (aguardando vínculo do Android)"
                else -> "Permissão de leitura de notificações desativada"
            }
            mapsListenerStatusText.setTextColor(
                when {
                    listenerEnabled && listenerBound -> scheme.tertiary
                    listenerEnabled -> scheme.primary
                    else -> scheme.error
                }
            )
            mapsLastCaptureText.text = "Última captura: ${bleManager.getLastMapsCaptureSummary()}"
            btnEnableListenerInCockpit.visibility = if (listenerEnabled) View.GONE else View.VISIBLE
        }

        if (::autoStartSubtitleText.isInitialized) {
            autoStartSubtitleText.text = if (autoStartOn) {
                if (BajajForegroundService.isRunning) {
                    "Ativo • serviço rodando e inicia com o celular"
                } else {
                    "Ativo • inicia automaticamente ao ligar o celular"
                }
            } else {
                "Pausado • ative para reconexão automática em fundo"
            }
            if (autoStartSwitch.isChecked != autoStartOn) {
                autoStartSwitch.isChecked = autoStartOn
            }
        }

        if (::recBatteryStatusText.isInitialized) {
            if (batteryIgnored) {
                recBatteryStatusText.text = "Ativo • protegido contra encerramento"
                recBatteryStatusText.setTextColor(scheme.tertiary)
                recBatteryIcon.setImageResource(R.drawable.ic_check)
                recBatteryIcon.imageTintList = ColorStateList.valueOf(scheme.tertiary)
            } else {
                recBatteryStatusText.text = "Recomendado • toque para liberar em segundo plano"
                recBatteryStatusText.setTextColor(scheme.warning)
                recBatteryIcon.setImageResource(R.drawable.ic_arrow_right)
                recBatteryIcon.imageTintList = ColorStateList.valueOf(scheme.warning)
            }
        }

        if (::recListenerStatusText.isInitialized) {
            when {
                listenerEnabled && listenerBound -> {
                    recListenerStatusText.text = "Ativo • capturando Google Maps, música e alertas"
                    recListenerStatusText.setTextColor(scheme.tertiary)
                    recListenerIcon.setImageResource(R.drawable.ic_check)
                    recListenerIcon.imageTintList = ColorStateList.valueOf(scheme.tertiary)
                }
                listenerEnabled -> {
                    recListenerStatusText.text = "Ativo nas configurações • reconectando vínculo"
                    recListenerStatusText.setTextColor(scheme.primary)
                    recListenerIcon.setImageResource(R.drawable.ic_check)
                    recListenerIcon.imageTintList = ColorStateList.valueOf(scheme.primary)
                }
                else -> {
                    recListenerStatusText.text = "Pendente • toque para ativar leitura"
                    recListenerStatusText.setTextColor(scheme.error)
                    recListenerIcon.setImageResource(R.drawable.ic_arrow_right)
                    recListenerIcon.imageTintList = ColorStateList.valueOf(scheme.error)
                }
            }
        }

        if (::recContactsStatusText.isInitialized) {
            if (callsAndContactsOk) {
                recContactsStatusText.text = "Ativo • nome do contato em telefone e WhatsApp"
                recContactsStatusText.setTextColor(scheme.tertiary)
                recContactsIcon.setImageResource(R.drawable.ic_check)
                recContactsIcon.imageTintList = ColorStateList.valueOf(scheme.tertiary)
            } else {
                recContactsStatusText.text = "Pendente • toque para exibir quem está ligando"
                recContactsStatusText.setTextColor(scheme.error)
                recContactsIcon.setImageResource(R.drawable.ic_arrow_right)
                recContactsIcon.imageTintList = ColorStateList.valueOf(scheme.error)
            }
        }

        if (::recSmsAndControlsStatusText.isInitialized) {
            if (smsAndControlsOk) {
                recSmsAndControlsStatusText.text = "Ativo • atender/recusar no guidão e SMS nativo"
                recSmsAndControlsStatusText.setTextColor(scheme.tertiary)
                recSmsAndControlsIcon.setImageResource(R.drawable.ic_check)
                recSmsAndControlsIcon.imageTintList = ColorStateList.valueOf(scheme.tertiary)
            } else {
                recSmsAndControlsStatusText.text = "Opcional • toque para liberar atender, recusar e SMS"
                recSmsAndControlsStatusText.setTextColor(scheme.warning)
                recSmsAndControlsIcon.setImageResource(R.drawable.ic_arrow_right)
                recSmsAndControlsIcon.imageTintList = ColorStateList.valueOf(scheme.warning)
            }
        }

        if (::quickReplySmsSummaryText.isInitialized) {
            quickReplySmsSummaryText.text = preferences.getQuickReplySmsText()
            homeNavSummaryText.text = preferences.getHomeNavigationQuery()
                .ifBlank { "Não definido (busca \"casa\" no Maps)" }
            preferredMediaSummaryText.text = resolveMediaPackageDisplay(preferences.getPreferredMediaPackage())
            if (clusterControlsSwitch.isChecked != preferences.isClusterControlsEnabled()) {
                clusterControlsSwitch.isChecked = preferences.isClusterControlsEnabled()
            }
            if (nativeSmsSwitch.isChecked != preferences.isNativeSmsEnabled()) {
                nativeSmsSwitch.isChecked = preferences.isNativeSmsEnabled()
            }
            if (autoSmsRejectSwitch.isChecked != preferences.isAutoSmsOnRejectEnabled()) {
                autoSmsRejectSwitch.isChecked = preferences.isAutoSmsOnRejectEnabled()
            }
        }

        if (::diagListenerText.isInitialized) {
            diagListenerText.text = "Leitura de notificações: ${if (listenerEnabled) (if (listenerBound) "Ativa e conectada" else "Ativa nas configurações") else "Desativada"}"
            diagMapsText.text = "Navegação: ${bleManager.getLastMapsCaptureSummary()}"
            diagMusicText.text = "Música: ${bleManager.getMusicSummary()}"
            val hsText = if (bleManager.getHeadsetConnected()) "Fone conectado" else "Fone desconectado"
            diagHeadsetVolText.text = "Áudio e fone: Volume ${bleManager.getVolumeLevel()}/10 • $hsText"
            diagCallText.text = "Chamadas: ${bleManager.getLastCallDebugSummary()}"
            diagAlertText.text = "Último alerta: ${bleManager.getLastAlertDispatchSummary()}"
            diagControlsText.text = "Comandos do guidão: ${bleManager.getLastControlDebugSummary()}"
            diagBleText.text = "Conexão com o painel: ${bleManager.getLastBleDebugEvent()}"
        }
    }

    // =========================================================================
    // HELPERS DE NAVEGAÇÃO & MANOBRAS
    // =========================================================================
    private fun createSegmentedChoiceChip(
        text: String,
        isLeft: Boolean,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val tv = TextView(this).apply {
            this.text = text
            M3Theme.applyType(this, 13f, bold = true, color = scheme.onSurface)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                if (isLeft) setMargins(0, 0, dp(4), 0) else setMargins(dp(4), 0, 0, 0)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        applyTactileFeedback(chip)
        chip.addView(tv)
        return chip to tv
    }

    private fun refreshNavigationModeUi() {
        if (!::blinkOffChip.isInitialized) return
        val applyStyle = { chip: LinearLayout, label: TextView, selected: Boolean ->
            chip.isSelected = selected
            chip.background = M3Theme.interactiveSurface(
                context = this,
                bgColor = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
                radiusPx = dp(999).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary,
                normalStrokeColor = if (selected) 0 else scheme.outlineVariant,
                normalStrokeWidthPx = if (selected) 0 else dp(1)
            )
            label.setTextColor(if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
        }
        applyStyle(blinkOffChip, blinkOffLabel, !simulateBlinkNearTurn)
        applyStyle(blinkOnChip, blinkOnLabel, simulateBlinkNearTurn)
        refreshManeuverTilesUi()
    }

    private fun refreshRoundaboutExitUi() {
        for (i in roundaboutChips.indices) {
            val exitNum = i + 1
            val selected = exitNum == selectedRoundaboutExit
            val chip = roundaboutChips[i]
            val label = roundaboutChipLabels[i]
            chip.isSelected = selected
            chip.background = M3Theme.interactiveSurface(
                context = this,
                bgColor = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
                radiusPx = dp(14).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary,
                normalStrokeColor = if (selected) 0 else scheme.outlineVariant,
                normalStrokeWidthPx = if (selected) 0 else dp(1)
            )
            label.setTextColor(if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
        }
    }

    private fun refreshManeuverTilesUi() {
        maneuverTileControllers.forEach { it.invoke() }
    }

    private fun createDynamicManeuverTile(spec: ManeuverSpec, isLeft: Boolean): LinearLayout {
        lateinit var iconView: ImageView
        lateinit var subView: TextView

        val computeTarget = {
            val dist = if (simulateBlinkNearTurn && spec.supportsBlink) {
                if (spec.solidCode == BajajProtocol.ARROW_DESTINATION_SOLID) 30 else 80
            } else {
                spec.solidDistMeters
            }
            val code = BajajProtocol.maneuverForDistance(spec.solidCode, dist)
            val exit = if (spec.usesRoundaboutExit) selectedRoundaboutExit else 0
            Triple(dist, code, exit)
        }

        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumHeight = dp(60)
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.surfaceContainerLow,
                radiusPx = dp(16).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                if (isLeft) setMargins(0, 0, dp(4), 0) else setMargins(dp(4), 0, 0, 0)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val (dist, code, exit) = computeTarget()
                val street = if (spec.usesRoundaboutExit) {
                    "${exit}ª saída • ${spec.streetSample}"
                } else {
                    spec.streetSample
                }
                bleManager.sendNavigation(
                    distanceMeters = dist,
                    maneuverCode = code,
                    text = street,
                    totalDistanceMeters = maxOf(dist, spec.etdSampleMeters),
                    roundaboutExit = exit,
                    sourceLabel = "Teste"
                )
                toast("Manobra enviada: ${spec.title}")
            }
        }
        applyTactileFeedback(tile)

        iconView = M3Theme.makeTintedIcon(this, spec.iconRes, 22, scheme.primary)
        (iconView.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(10), 0)
        tile.addView(iconView)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val title = TextView(this).apply {
            text = spec.title
            M3Theme.applyType(this, 14f, bold = true, color = scheme.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(title)

        subView = TextView(this).apply {
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(subView)
        tile.addView(textCol)

        val syncUi: () -> Unit = {
            val (dist, code, exit) = computeTarget()
            val isBlink = BajajProtocol.isBlinkCode(code)
            subView.text = when {
                spec.hardwareNote != null -> "$dist m • ${spec.hardwareNote}"
                spec.usesRoundaboutExit -> "$dist m • ${exit}ª saída"
                !spec.supportsBlink -> "$dist m • Seta fixa"
                isBlink -> "$dist m • Pisca"
                else -> "$dist m • Seta fixa"
            }
            val tint = when {
                spec.solidCode == BajajProtocol.ARROW_WRONG_WAY -> scheme.error
                isBlink -> scheme.warning
                spec.isAlertAccent -> scheme.error
                spec.hardwareNote != null -> scheme.onSurfaceVariant
                else -> scheme.primary
            }
            iconView.imageTintList = ColorStateList.valueOf(tint)
        }
        syncUi()
        maneuverTileControllers.add(syncUi)

        return tile
    }

    // =========================================================================
    // 6B. ABA 3: PERFIL, ODÔMETRO GPS & REVISÕES BAJAJ
    // =========================================================================
    private fun buildProfileTab(parent: LinearLayout) {
        // Banner condicional de revisão próxima / vencida
        profileAlertBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = M3Theme.roundedSurface(scheme.warningContainer, dp(M3Shape.LARGE_DP).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(10)) }
            visibility = View.GONE
        }

        val bannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        profileAlertIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_warning, 22, scheme.onWarningContainer)
        (profileAlertIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), dp(12), 0)
        bannerRow.addView(profileAlertIcon)

        val bannerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        profileAlertTitle = TextView(this).apply {
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onWarningContainer)
        }
        bannerTextCol.addView(profileAlertTitle)

        profileAlertDetail = TextView(this).apply {
            M3Theme.applyRole(this, M3TypeRole.BODY_MEDIUM, scheme.onWarningContainer)
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(0f, 1.25f)
        }
        bannerTextCol.addView(profileAlertDetail)
        bannerRow.addView(bannerTextCol)
        profileAlertBanner.addView(bannerRow)

        profileAlertActionBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.surface,
                radiusPx = dp(999).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(12), 0, 0) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val snap = lastMaintenanceSnapshot ?: maintenanceManager.getSnapshot()
                openMilestoneActionDialog(
                    snap.milestones.firstOrNull { it.km == snap.nextMilestoneKm }
                        ?: snap.milestones.last()
                )
            }
        }
        applyTactileFeedback(profileAlertActionBtn)
        val bannerBtnIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_check, 18, scheme.onSurface)
        (bannerBtnIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(8), 0)
        profileAlertActionBtn.addView(bannerBtnIcon)
        profileAlertActionLabel = TextView(this).apply {
            text = "Registrar revisão feita"
            M3Theme.applyRole(this, M3TypeRole.LABEL_LARGE, scheme.onSurface)
            maxLines = 1
        }
        profileAlertActionBtn.addView(profileAlertActionLabel)
        profileAlertBanner.addView(profileAlertActionBtn)
        parent.addView(profileAlertBanner)

        // Card de identidade do piloto e moto
        val riderAndBikeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(M3Shape.LARGE_DP).toFloat())
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }

        val riderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(10), dp(12))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = 0f,
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Editar nome do piloto"
            setOnClickListener { openEditRiderNameDialog() }
        }

        profileAvatarCircle = FrameLayout(this).apply {
            background = M3Theme.roundedSurface(scheme.primaryContainer, dp(24).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(0, 0, dp(14), 0)
            }
        }
        profileAvatarIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_person, 22, scheme.onPrimaryContainer)
        profileAvatarIcon.layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER }
        profileAvatarCircle.addView(profileAvatarIcon)

        profileAvatarText = TextView(this).apply {
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onPrimaryContainer)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        profileAvatarCircle.addView(profileAvatarText)
        riderRow.addView(profileAvatarCircle)

        val riderTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(6), 0)
            }
        }
        profileRiderNameText = TextView(this).apply {
            text = "Definir nome do piloto"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        riderTextCol.addView(profileRiderNameText)

        riderTextCol.addView(TextView(this).apply {
            text = "Piloto • Toque para alterar"
            M3Theme.applyRole(this, M3TypeRole.BODY_SMALL, scheme.onSurfaceVariant)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        riderRow.addView(riderTextCol)

        val editRiderBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(24).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Alterar nome do piloto"
            setOnClickListener { openEditRiderNameDialog() }
        }
        val editRiderIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_edit, 20, scheme.onSurfaceVariant)
        editRiderIcon.layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER }
        editRiderBtn.addView(editRiderIcon)
        riderRow.addView(editRiderBtn)
        riderAndBikeCard.addView(riderRow)

        riderAndBikeCard.addView(createHorizontalHairline())

        val bikeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(16), dp(12), dp(10), dp(12))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = 0f,
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Alterar nome da moto"
            setOnClickListener {
                val pinnedAddr = preferences.getPinnedAddress()
                if (pinnedAddr != null) {
                    val orig = preferences.getPinnedName() ?: "Moto Bajaj"
                    openRenameDeviceDialog(pinnedAddr, orig)
                } else {
                    switchTab(0)
                    toast("Fixe sua moto no Cockpit primeiro")
                }
            }
        }

        val bikeCircle = FrameLayout(this).apply {
            background = M3Theme.roundedSurface(scheme.secondaryContainer, dp(22).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(0, 0, dp(14), 0)
            }
        }
        val bikeCircleIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_two_wheeler, 22, scheme.onSecondaryContainer)
        bikeCircleIcon.layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER }
        bikeCircle.addView(bikeCircleIcon)
        bikeRow.addView(bikeCircle)

        val bikeTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(6), 0)
            }
        }
        profileBikeNameText = TextView(this).apply {
            text = "Moto Bajaj"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bikeTextCol.addView(profileBikeNameText)

        profileBikeSubText = TextView(this).apply {
            text = "Plano padrão Bajaj Brasil"
            M3Theme.applyRole(this, M3TypeRole.BODY_SMALL, scheme.onSurfaceVariant)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        bikeTextCol.addView(profileBikeSubText)
        bikeRow.addView(bikeTextCol)

        val editBikeBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(24).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Alterar nome da moto"
            setOnClickListener {
                val pinnedAddr = preferences.getPinnedAddress()
                if (pinnedAddr != null) {
                    val orig = preferences.getPinnedName() ?: "Moto Bajaj"
                    openRenameDeviceDialog(pinnedAddr, orig)
                } else {
                    switchTab(0)
                    toast("Fixe sua moto no Cockpit primeiro")
                }
            }
        }
        val editBikeIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_edit, 20, scheme.onSurfaceVariant)
        editBikeIcon.layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER }
        editBikeBtn.addView(editBikeIcon)
        bikeRow.addView(editBikeBtn)
        riderAndBikeCard.addView(bikeRow)

        parent.addView(riderAndBikeCard)

        // Hero de Odômetro & Próxima Revisão
        val odoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = M3Theme.roundedSurface(scheme.surfaceContainerHigh, dp(24).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val odoHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
        }
        val odoTag = TextView(this).apply {
            text = "Odômetro estimado"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        M3Theme.markAccessibilityHeading(odoTag)
        odoHeaderRow.addView(odoTag)

        profileGpsStatusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = M3Theme.roundedSurface(scheme.surfaceContainerHighest, dp(M3Shape.FULL_DP).toFloat())
        }
        profileGpsStatusIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_location_pin, 14, scheme.onSurfaceVariant)
        (profileGpsStatusIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(5), 0)
        profileGpsStatusPill.addView(profileGpsStatusIcon)

        profileGpsStatusText = TextView(this).apply {
            text = "Aguardando moto"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
            includeFontPadding = false
            maxLines = 1
        }
        profileGpsStatusPill.addView(profileGpsStatusText)
        odoHeaderRow.addView(profileGpsStatusPill)
        odoCard.addView(odoHeaderRow)

        val kmValueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
        }
        val kmTextGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        profileOdometerValueText = TextView(this).apply {
            text = "0,0"
            M3Theme.applyRole(this, M3TypeRole.DISPLAY_NUMERAL, scheme.onSurface)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            maxLines = 1
        }
        kmTextGroup.addView(profileOdometerValueText)
        kmTextGroup.addView(TextView(this).apply {
            text = " km"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.primary)
            setPadding(dp(2), 0, 0, dp(4))
            includeFontPadding = false
        })
        kmValueRow.addView(kmTextGroup)

        // Botões de ajuste rápido de 1 em 1 km inteiro (-1 km e +1 km)
        val quickStepGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnQuickMinus = TextView(this).apply {
            text = "-1 km"
            M3Theme.applyRole(this, M3TypeRole.LABEL_LARGE, scheme.onSurface)
            gravity = Gravity.CENTER
            minimumWidth = dp(56)
            minimumHeight = dp(48)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(12).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary,
                normalStrokeColor = scheme.outlineVariant,
                normalStrokeWidthPx = dp(1)
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Diminuir 1 quilômetro inteiro do odômetro"
            setOnClickListener {
                val res = maintenanceManager.adjustOdometerKmInteger(-1)
                val diffPct = ((res.calibrationFactor - 1.0) * 100).toInt()
                val sign = if (diffPct > 0) "+$diffPct%" else "$diffPct%"
                if (res.calibrationUpdated) {
                    toast("Odômetro: ${res.newWholeKm} km (-1 km) • Calibração GPS inteligente: $sign")
                } else {
                    toast("Odômetro: ${res.newWholeKm} km (-1 km)")
                }
            }
        }
        val btnQuickPlus = TextView(this).apply {
            text = "+1 km"
            M3Theme.applyRole(this, M3TypeRole.LABEL_LARGE, scheme.onSecondaryContainer)
            gravity = Gravity.CENTER
            minimumWidth = dp(56)
            minimumHeight = dp(48)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.secondaryContainer,
                radiusPx = dp(12).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Aumentar 1 quilômetro inteiro do odômetro"
            setOnClickListener {
                val res = maintenanceManager.adjustOdometerKmInteger(1)
                val diffPct = ((res.calibrationFactor - 1.0) * 100).toInt()
                val sign = if (diffPct > 0) "+$diffPct%" else "$diffPct%"
                if (res.calibrationUpdated) {
                    toast("Odômetro: ${res.newWholeKm} km (+1 km) • Calibração GPS inteligente: $sign")
                } else {
                    toast("Odômetro: ${res.newWholeKm} km (+1 km)")
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), 0, 0, 0) }
        }
        quickStepGroup.addView(btnQuickMinus)
        quickStepGroup.addView(btnQuickPlus)
        kmValueRow.addView(quickStepGroup)
        odoCard.addView(kmValueRow)

        // Sub-aviso de simulação via GPS (integrado sem cartão aninhado)
        val noticeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(2), 0, dp(14))
        }
        val noticeIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_location_pin, 16, scheme.onSurfaceVariant)
        (noticeIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), dp(8), 0)
        noticeRow.addView(noticeIcon)

        profileGpsNoticeText = TextView(this).apply {
            text = "Estimativa via GPS quando conectado à moto. Como o painel BLE não envia o odômetro real, ajuste quando necessário."
            M3Theme.applyRole(this, M3TypeRole.BODY_SMALL, scheme.onSurfaceVariant)
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        noticeRow.addView(profileGpsNoticeText)
        odoCard.addView(noticeRow)

        // Barra de progresso até próxima revisão
        val progTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
            background = M3Theme.roundedSurface(scheme.outlineVariant, dp(4).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply { setMargins(0, 0, 0, dp(14)) }
        }
        profileProgressFill = View(this).apply {
            background = M3Theme.roundedSurface(scheme.primary, dp(4).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        }
        profileProgressEmpty = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 100f)
        }
        progTrack.addView(profileProgressFill)
        progTrack.addView(profileProgressEmpty)
        odoCard.addView(progTrack)

        // Linha com Próxima revisão (km) vs Prazo por tempo
        val metricsRow = createTwoColRow(bottomMarginDp = 14)
        val kmMetricCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        kmMetricCol.addView(TextView(this).apply {
            text = "Próxima revisão"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
        })
        profileNextServiceKmText = TextView(this).apply {
            text = "1.000 km"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            fontFeatureSettings = "tnum"
            setPadding(0, dp(2), 0, dp(1))
        }
        kmMetricCol.addView(profileNextServiceKmText)
        profileNextServiceRemText = TextView(this).apply {
            text = "Faltam 1.000 km"
            M3Theme.applyRole(this, M3TypeRole.BODY_SMALL, scheme.onSurfaceVariant)
            fontFeatureSettings = "tnum"
        }
        kmMetricCol.addView(profileNextServiceRemText)
        metricsRow.addView(kmMetricCol)

        val dateMetricCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(dp(8), 0, 0, 0)
            }
        }
        dateMetricCol.addView(TextView(this).apply {
            text = "Prazo por tempo"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
        })
        profileNextServiceDateText = TextView(this).apply {
            text = "Definir data"
            M3Theme.applyRole(this, M3TypeRole.TITLE_MEDIUM, scheme.onSurface)
            fontFeatureSettings = "tnum"
            setPadding(0, dp(2), 0, dp(1))
        }
        dateMetricCol.addView(profileNextServiceDateText)
        profileNextServiceDaysText = TextView(this).apply {
            text = "Toque em Ajustar km"
            M3Theme.applyRole(this, M3TypeRole.BODY_SMALL, scheme.onSurfaceVariant)
            fontFeatureSettings = "tnum"
        }
        dateMetricCol.addView(profileNextServiceDaysText)
        metricsRow.addView(dateMetricCol)
        odoCard.addView(metricsRow)

        val odoBtnRow = createTwoColRow(bottomMarginDp = 0)
        val btnAdjustKm = createM3Button(
            btnText = "Ajustar km",
            iconRes = R.drawable.ic_edit,
            bgColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 0
        ) {
            openEditOdometerDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }
        val btnMarkDone = createM3Button(
            btnText = "Registrar",
            iconRes = R.drawable.ic_check,
            bgColor = scheme.primary,
            contentColor = scheme.onPrimary,
            rippleColor = scheme.rippleOnPrimary,
            bottomMarginDp = 0
        ) {
            val snap = lastMaintenanceSnapshot ?: maintenanceManager.getSnapshot()
            openMilestoneActionDialog(
                snap.milestones.firstOrNull { it.km == snap.nextMilestoneKm }
                    ?: snap.milestones.last()
            )
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
        odoBtnRow.addView(btnAdjustKm)
        odoBtnRow.addView(btnMarkDone)
        odoCard.addView(odoBtnRow)
        parent.addView(odoCard)

        // Configurações do Odômetro via GPS
        addSectionHeader(parent, "Odômetro via GPS")
        val gpsSettingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }

        val gpsEnablePair = createM3SwitchRow(
            title = "Somar km automaticamente via GPS",
            subtitle = "Estima quilômetros rodados acima de 10 km/h",
            iconRes = R.drawable.ic_location_pin,
            checked = preferences.isGpsOdometerEnabled(),
            contentDesc = "Ativar soma automática de quilometragem via GPS"
        ) { checked ->
            if (checked && !maintenanceManager.hasLocationPermission()) {
                requestOdometerLocationPermission()
            }
            maintenanceManager.setGpsOdometerSettings(
                enabled = checked,
                onlyWhenConnected = preferences.isGpsOdometerOnlyWhenConnected()
            )
            toast(if (checked) "Odômetro GPS ativado" else "Odômetro GPS pausado")
        }
        profileGpsEnabledSwitch = gpsEnablePair.second
        gpsSettingsCard.addView(gpsEnablePair.first)
        gpsSettingsCard.addView(createHorizontalHairline())

        val gpsBleOnlyPair = createM3SwitchRow(
            title = "Somente conectado à moto",
            subtitle = "Recomendado — economiza bateria e evita somar outros trajetos",
            iconRes = R.drawable.ic_bluetooth,
            checked = preferences.isGpsOdometerOnlyWhenConnected(),
            contentDesc = "Somar km via GPS apenas enquanto conectado ao painel da moto"
        ) { checked ->
            maintenanceManager.setGpsOdometerSettings(
                enabled = preferences.isGpsOdometerEnabled(),
                onlyWhenConnected = checked
            )
            toast(if (checked) "Soma restrita à moto conectada" else "Soma ativa mesmo sem conexão com o painel")
        }
        profileGpsOnlyConnectedSwitch = gpsBleOnlyPair.second
        gpsSettingsCard.addView(gpsBleOnlyPair.first)
        parent.addView(gpsSettingsCard)

        // Plano unificado de revisões Bajaj
        addSectionHeader(parent, "Plano de revisões Bajaj")
        parent.addView(TextView(this).apply {
            text = "1ª revisão em 1.000 km (1 ou 3 meses) e demais a cada 5.000 km ou 6 meses, o que ocorrer primeiro. Toque em qualquer etapa para marcar ou editar."
            M3Theme.applyRole(this, M3TypeRole.BODY_SMALL, scheme.onSurfaceVariant)
            setPadding(dp(4), 0, dp(4), dp(8))
            setLineSpacing(0f, 1.25f)
        })

        profileScheduleListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
        parent.addView(profileScheduleListContainer)

        // Support & Donation Section (Material 3 Card)
        addSectionHeader(parent, getString(R.string.donation_section_title))
        val donationCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = M3Theme.roundedSurface(scheme.surfaceContainerLow, dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(16)) }
        }

        val donationDesc = TextView(this).apply {
            text = getString(R.string.donation_desc)
            M3Theme.applyRole(this, M3TypeRole.BODY_MEDIUM, scheme.onSurfaceVariant)
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, dp(12))
        }
        donationCard.addView(donationDesc)

        val donationBtnRow = createTwoColRow(bottomMarginDp = 0)

        // Botão PIX (Copiar chave PIX e tentar abrir app de banco ou Toast)
        val pixKey = "d5c50784-cdd3-4426-8778-d65e841c8dbb"
        val btnPix = createM3Button(
            btnText = getString(R.string.donation_pix_btn),
            iconRes = R.drawable.ic_check,
            bgColor = scheme.primary,
            contentColor = scheme.onPrimary,
            rippleColor = scheme.rippleOnPrimary,
            bottomMarginDp = 0
        ) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null) {
                val clip = android.content.ClipData.newPlainText("Chave PIX", pixKey)
                clipboard.setPrimaryClip(clip)
            }
            toast(getString(R.string.donation_pix_copied))

            // Tenta abrir app bancário compatível ou seletor
            try {
                val bankIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://pix.bcb.gov.br")
                }
                startActivity(Intent.createChooser(bankIntent, "Abrir app de pagamento"))
            } catch (_: Exception) {
                // Toast já confirma chave copiada
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }

        // Botão Ko-fi (Abre o link web do Ko-fi)
        val btnKofi = createM3Button(
            btnText = getString(R.string.donation_kofi_btn),
            iconRes = R.drawable.ic_search,
            bgColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            rippleColor = scheme.rippleOnSurface,
            bottomMarginDp = 0
        ) {
            try {
                val kofiIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/jonasoliveira"))
                startActivity(kofiIntent)
            } catch (e: Exception) {
                toast("Erro ao abrir Ko-fi: ${e.message}")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }

        donationBtnRow.addView(btnPix)
        donationBtnRow.addView(btnKofi)
        donationCard.addView(donationBtnRow)
        parent.addView(donationCard)
    }

    private fun refreshMaintenanceUi(snapshot: MaintenanceSnapshot) {
        lastMaintenanceSnapshot = snapshot

        // 1. Sub-aviso discreto no Cockpit (visível apenas quando NEAR ou OVERDUE)
        if (::cockpitMaintenanceNoticeCard.isInitialized) {
            if (snapshot.alertLevel == MaintenanceAlertLevel.OK) {
                cockpitMaintenanceNoticeCard.visibility = View.GONE
            } else {
                val isOverdue = snapshot.alertLevel == MaintenanceAlertLevel.OVERDUE
                val bg = if (isOverdue) scheme.errorContainer else scheme.warningContainer
                val fg = if (isOverdue) scheme.onErrorContainer else scheme.onWarningContainer
                cockpitMaintenanceNoticeCard.visibility = View.VISIBLE
                cockpitMaintenanceNoticeCard.background = M3Theme.interactiveSurface(
                    context = this,
                    bgColor = bg,
                    radiusPx = dp(14).toFloat(),
                    rippleColor = scheme.rippleOnSurface,
                    focusStrokeColor = scheme.primary
                )
                cockpitMaintenanceNoticeIcon.imageTintList = ColorStateList.valueOf(fg)
                cockpitMaintenanceNoticeTitle.text = snapshot.alertHeadline
                cockpitMaintenanceNoticeTitle.setTextColor(fg)
                cockpitMaintenanceNoticeDetail.text = "${snapshot.alertDetail} Toque para abrir Perfil."
                cockpitMaintenanceNoticeDetail.setTextColor(fg)
                cockpitMaintenanceNoticeChevron.imageTintList = ColorStateList.valueOf(fg)
                cockpitMaintenanceNoticeCard.contentDescription =
                    "${snapshot.alertHeadline}. ${snapshot.alertDetail}. Toque para abrir a aba Perfil."
            }
        }

        if (!::profileOdometerValueText.isInitialized) return

        // 2. Banner no topo do Perfil
        if (snapshot.alertLevel == MaintenanceAlertLevel.OK) {
            profileAlertBanner.visibility = View.GONE
        } else {
            val isOverdue = snapshot.alertLevel == MaintenanceAlertLevel.OVERDUE
            val bg = if (isOverdue) scheme.errorContainer else scheme.warningContainer
            val fg = if (isOverdue) scheme.onErrorContainer else scheme.onWarningContainer
            profileAlertBanner.visibility = View.VISIBLE
            profileAlertBanner.background = M3Theme.roundedSurface(bg, dp(M3Shape.LARGE_DP).toFloat())
            profileAlertIcon.imageTintList = ColorStateList.valueOf(fg)
            profileAlertTitle.text = snapshot.alertHeadline
            profileAlertTitle.setTextColor(fg)
            profileAlertDetail.text = snapshot.alertDetail
            profileAlertDetail.setTextColor(fg)
            profileAlertActionLabel.text = "Registrar revisão de ${maintenanceManager.formatIntKm(snapshot.nextMilestoneKm)} km"
        }

        // 3. Card do piloto
        val riderName = snapshot.riderName
        if (riderName.isNotBlank()) {
            profileRiderNameText.text = riderName
            profileAvatarIcon.visibility = View.GONE
            profileAvatarText.visibility = View.VISIBLE
            profileAvatarText.text = riderName.trim().first().uppercaseChar().toString()
        } else {
            profileRiderNameText.text = "Definir nome do piloto"
            profileAvatarIcon.visibility = View.VISIBLE
            profileAvatarText.visibility = View.GONE
        }
        val pinnedAddr = preferences.getPinnedAddress()
        if (pinnedAddr != null) {
            val origName = preferences.getPinnedName() ?: "Moto Bajaj"
            val customName = preferences.getCustomDeviceName(pinnedAddr)
            val displayName = preferences.getDisplayName(pinnedAddr, origName)
            profileBikeNameText.text = displayName
            profileBikeSubText.text = if (customName != null && !customName.equals(origName, ignoreCase = true)) {
                "Original: $origName • Toque para alterar nome"
            } else {
                "Moto fixada • Toque para alterar nome"
            }
        } else {
            profileBikeNameText.text = "Nenhuma moto fixada"
            profileBikeSubText.text = "Toque para fixar sua moto no Cockpit"
        }

        // 4. Hero Odômetro & Próxima Revisão
        profileOdometerValueText.text = maintenanceManager.formatDecimalKm(snapshot.currentKm)

        val pillBg: Int
        val pillFg: Int
        when {
            !snapshot.gpsEnabled -> {
                pillBg = scheme.surfaceContainerHighest
                pillFg = scheme.onSurfaceVariant
            }
            snapshot.isGpsActivelyTracking && snapshot.lastSpeedKmh >= 10 -> {
                pillBg = scheme.tertiaryContainer
                pillFg = scheme.onTertiaryContainer
            }
            snapshot.gpsOnlyWhenConnected && !snapshot.isBleConnected -> {
                pillBg = scheme.secondaryContainer
                pillFg = scheme.onSecondaryContainer
            }
            !maintenanceManager.hasLocationPermission() -> {
                pillBg = scheme.errorContainer
                pillFg = scheme.onErrorContainer
            }
            else -> {
                pillBg = scheme.primaryContainer
                pillFg = scheme.onPrimaryContainer
            }
        }
        profileGpsStatusPill.background = M3Theme.roundedSurface(pillBg, dp(M3Shape.FULL_DP).toFloat())
        profileGpsStatusIcon.imageTintList = ColorStateList.valueOf(pillFg)
        profileGpsStatusText.text = snapshot.gpsStatusText
        profileGpsStatusText.setTextColor(pillFg)

        val gpsDeltaStr = maintenanceManager.formatDecimalKm(snapshot.gpsAddedKmSinceSync)
        val factorPct = ((snapshot.calibrationFactor - 1.0) * 100).toInt()
        val calibInfo = if (factorPct != 0) {
            val sign = if (factorPct > 0) "+$factorPct%" else "$factorPct%"
            " • Calibração inteligente ativa: $sign"
        } else ""

        profileGpsNoticeText.text = if (snapshot.gpsAddedKmSinceSync >= 0.05) {
            "Estimativa via GPS (+$gpsDeltaStr km rodados)$calibInfo."
        } else {
            "Estimativa via GPS em movimento acima de 10 km/h$calibInfo."
        }

        val pct = (snapshot.progressFraction * 100f).coerceIn(0f, 100f)
        val pctInt = pct.toInt()
        if (pctInt != lastProgressPct) {
            lastProgressPct = pctInt
            (profileProgressFill.layoutParams as LinearLayout.LayoutParams).weight = pct
            (profileProgressEmpty.layoutParams as LinearLayout.LayoutParams).weight = (100f - pct)
            profileProgressFill.requestLayout()
        }
        val progColor = when (snapshot.alertLevel) {
            MaintenanceAlertLevel.OVERDUE -> scheme.error
            MaintenanceAlertLevel.NEAR -> scheme.warning
            MaintenanceAlertLevel.OK -> scheme.primary
        }
        profileProgressFill.background = M3Theme.roundedSurface(progColor, dp(4).toFloat())

        profileNextServiceKmText.text = "${maintenanceManager.formatIntKm(snapshot.nextMilestoneKm)} km"
        profileNextServiceRemText.text = when {
            snapshot.kmRemaining <= 0.0 -> {
                val passed = ceil(-snapshot.kmRemaining).toInt().coerceAtLeast(0)
                if (passed == 0) "Marco atingido" else "Passou ${maintenanceManager.formatIntKm(passed)} km"
            }
            else -> {
                val rem = ceil(snapshot.kmRemaining).toInt().coerceAtLeast(1)
                "Faltam ${maintenanceManager.formatIntKm(rem)} km"
            }
        }
        profileNextServiceRemText.setTextColor(
            when {
                snapshot.kmRemaining <= 0.0 -> scheme.error
                snapshot.kmRemaining <= MaintenanceManager.NEAR_KM_THRESHOLD -> scheme.warning
                else -> scheme.onSurfaceVariant
            }
        )

        if (snapshot.hasBaseDate && snapshot.daysRemaining != null) {
            profileNextServiceDateText.text = maintenanceManager.formatDateShort(snapshot.dueDateMs)
            val d = snapshot.daysRemaining
            profileNextServiceDaysText.text = formatRemainingDaysNatural(d)
            profileNextServiceDaysText.setTextColor(
                when {
                    d <= 0 -> scheme.error
                    d <= MaintenanceManager.NEAR_DAYS_THRESHOLD -> scheme.warning
                    else -> scheme.onSurfaceVariant
                }
            )
        } else {
            profileNextServiceDateText.text = "Definir data"
            profileNextServiceDaysText.text = "Toque em Ajustar km"
            profileNextServiceDaysText.setTextColor(scheme.onSurfaceVariant)
        }

        // 5. Switches GPS
        if (profileGpsEnabledSwitch.isChecked != snapshot.gpsEnabled) {
            profileGpsEnabledSwitch.isChecked = snapshot.gpsEnabled
        }
        if (profileGpsOnlyConnectedSwitch.isChecked != snapshot.gpsOnlyWhenConnected) {
            profileGpsOnlyConnectedSwitch.isChecked = snapshot.gpsOnlyWhenConnected
        }

        // 6. Lista de revisões (só reconstrói se mudou)
        val scheduleSig = "${snapshot.firstServiceMonths}|${snapshot.alertLevel}|${snapshot.nextMilestoneKm}|" +
            snapshot.milestones.joinToString(",") { "${it.km}:${it.isCompleted}:${it.isCurrentTarget}" }
        if (scheduleSig != lastScheduleSignature) {
            lastScheduleSignature = scheduleSig
            profileScheduleListContainer.removeAllViews()
            snapshot.milestones.forEachIndexed { idx, milestone ->
                if (idx > 0) {
                    profileScheduleListContainer.addView(createHorizontalHairline())
                }
                profileScheduleListContainer.addView(createMilestoneRow(milestone, snapshot.alertLevel))
            }
        }
    }

    private fun formatRemainingDaysNatural(d: Int): String = when {
        d < 0 -> {
            val past = -d
            when {
                past == 1 -> "Venceu ontem"
                past < 30 -> "Venceu há $past dias"
                past in 30..59 -> "Venceu há 1 mês"
                else -> "Venceu há ${past / 30} meses"
            }
        }
        d == 0 -> "Vence hoje"
        d == 1 -> "Falta 1 dia"
        d < 30 -> "Faltam $d dias"
        d in 30..59 -> {
            val rem = d % 30
            when (rem) {
                0 -> "Falta 1 mês"
                1 -> "Falta 1 mês e 1 dia"
                else -> "Falta 1 mês e $rem dias"
            }
        }
        else -> {
            val months = d / 30
            val rem = d % 30
            when (rem) {
                0 -> "Faltam $months meses"
                1 -> "Faltam $months meses e 1 dia"
                else -> "Faltam $months meses e $rem dias"
            }
        }
    }

    private fun createMilestoneRow(milestone: ServiceMilestone, alertLevel: MaintenanceAlertLevel): LinearLayout {
        val rowBg = when {
            milestone.isCurrentTarget -> scheme.surfaceContainer
            else -> Color.TRANSPARENT
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = rowBg,
                radiusPx = 0f,
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { openMilestoneActionDialog(milestone) }
        }

        val iconRes: Int
        val iconTint: Int
        when {
            milestone.isCompleted -> {
                iconRes = R.drawable.ic_check
                iconTint = scheme.tertiary
            }
            milestone.isCurrentTarget && alertLevel == MaintenanceAlertLevel.OVERDUE -> {
                iconRes = R.drawable.ic_warning
                iconTint = scheme.error
            }
            milestone.isCurrentTarget && alertLevel == MaintenanceAlertLevel.NEAR -> {
                iconRes = R.drawable.ic_warning
                iconTint = scheme.warning
            }
            milestone.isCurrentTarget -> {
                iconRes = R.drawable.ic_build_wrench
                iconTint = scheme.primary
            }
            else -> {
                iconRes = R.drawable.ic_build_wrench
                iconTint = scheme.onSurfaceVariant
            }
        }
        val leadIcon = M3Theme.makeTintedIcon(this, iconRes, 22, iconTint)
        (leadIcon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(14), 0)
        row.addView(leadIcon)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        val statusSuffix = when {
            milestone.isCompleted -> " • Concluída"
            milestone.isCurrentTarget && alertLevel == MaintenanceAlertLevel.OVERDUE -> " • Vencida"
            milestone.isCurrentTarget && alertLevel == MaintenanceAlertLevel.NEAR -> " • Próxima"
            milestone.isCurrentTarget -> " • Atual"
            else -> ""
        }
        textCol.addView(TextView(this).apply {
            text = "${milestone.order}ª revisão • ${maintenanceManager.formatIntKm(milestone.km)} km$statusSuffix"
            M3Theme.applyType(
                this,
                14f,
                bold = true,
                color = if (milestone.isCompleted) scheme.tertiary else scheme.onSurface
            )
            fontFeatureSettings = "tnum"
        })
        textCol.addView(TextView(this).apply {
            text = "Prazo: ${milestone.timeRuleLabel}"
            M3Theme.applyType(this, 12f, bold = true, color = scheme.onSurfaceVariant)
            setPadding(0, dp(2), 0, 0)
        })
        textCol.addView(TextView(this).apply {
            text = milestone.summary
            M3Theme.applyType(this, 12f, bold = false, color = scheme.onSurfaceVariant)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol)

        val trailing = M3Theme.makeTintedIcon(this, R.drawable.ic_arrow_right, 18, scheme.onSurfaceVariant)
        row.addView(trailing)
        return row
    }

    private fun openEditRiderNameDialog() {
        val currentName = preferences.getRiderName()
        openTextSettingDialog(
            title = "Nome do piloto",
            subtitle = "Exibido no seu perfil do RideNav Link.",
            fieldHint = "Seu nome ou apelido",
            initialValue = currentName,
            neutralText = "Limpar"
        ) { newName ->
            maintenanceManager.setRiderName(newName)
            toast(if (newName.isBlank()) "Nome do piloto removido" else "Nome salvo: $newName")
        }
    }

    private fun openEditOdometerDialog() {
        val snap = lastMaintenanceSnapshot ?: maintenanceManager.getSnapshot()
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)

        // Campo de quilometragem atual
        content.addView(TextView(this).apply {
            text = "Quilometragem atual do painel (km)"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(6))
        })
        val kmFieldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            background = M3Theme.textFieldSurface(this@MainActivity, scheme, dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
        }
        val kmInitial = if (snap.currentKm > 0.0) String.format(Locale.US, "%.1f", snap.currentKm) else ""
        val kmEdit = EditText(this).apply {
            setText(kmInitial)
            setSelection(kmInitial.length)
            hint = "Ex.: 4850.5"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(scheme.onSurface)
            setHintTextColor(scheme.onSurfaceVariant)
            M3Theme.applyType(this, 16f, bold = false, color = scheme.onSurface)
            background = null
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 1
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        kmFieldRow.addView(kmEdit)
        content.addView(kmFieldRow)

        // Botões de passo inteiro rápido (-1 km e +1 km) dentro do diálogo
        val stepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
        }
        val btnStepMinus = TextView(this).apply {
            text = "– 1 km"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurface)
            gravity = Gravity.CENTER
            minimumHeight = dp(40)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(8).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary,
                normalStrokeColor = scheme.outlineVariant,
                normalStrokeWidthPx = dp(1)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val rawText = kmEdit.text?.toString()?.trim() ?: ""
                val curVal = rawText.toDoubleOrNull() ?: 0.0
                val raw = (curVal + 0.5).toInt() - 1
                val nextVal = if (raw < 0) 0 else raw
                kmEdit.setText(nextVal.toString())
                kmEdit.setSelection(kmEdit.text.length)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        }
        val btnStepPlus = TextView(this).apply {
            text = "+ 1 km"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSecondaryContainer)
            gravity = Gravity.CENTER
            minimumHeight = dp(40)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.secondaryContainer,
                radiusPx = dp(8).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val rawText = kmEdit.text?.toString()?.trim() ?: ""
                val curVal = rawText.toDoubleOrNull() ?: 0.0
                val nextVal = (curVal + 0.5).toInt() + 1
                kmEdit.setText(nextVal.toString())
                kmEdit.setSelection(kmEdit.text.length)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
        stepRow.addView(btnStepMinus)
        stepRow.addView(btnStepPlus)
        content.addView(stepRow)

        // Seletor de última revisão já feita
        content.addView(TextView(this).apply {
            text = "Última revisão já realizada"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(6))
        })
        var selectedLastCompleted = preferences.getCompletedServiceMilestones().maxOrNull() ?: 0
        val milestoneOptions = mutableListOf(0, 1000, 5000, 10000, 15000, 20000, 25000, 30000)
        if (selectedLastCompleted > 30000 && !milestoneOptions.contains(selectedLastCompleted)) {
            milestoneOptions.add(selectedLastCompleted)
        }
        val msScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
        }
        val msRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val msButtons = mutableListOf<Triple<Int, LinearLayout, TextView>>()
        val refreshMsChips = {
            for ((kmVal, chip, tv) in msButtons) {
                val sel = kmVal == selectedLastCompleted
                chip.background = M3Theme.interactiveSurface(
                    context = this,
                    bgColor = if (sel) scheme.secondaryContainer else scheme.surfaceContainer,
                    radiusPx = dp(12).toFloat(),
                    rippleColor = scheme.rippleOnSurface,
                    focusStrokeColor = scheme.primary,
                    normalStrokeColor = if (sel) 0 else scheme.outlineVariant,
                    normalStrokeWidthPx = if (sel) 0 else dp(1)
                )
                tv.setTextColor(if (sel) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
            }
        }
        milestoneOptions.forEachIndexed { i, kmVal ->
            val tv = TextView(this).apply {
                text = if (kmVal == 0) "Nenhuma (0 km)" else "${maintenanceManager.formatIntKm(kmVal)} km"
                M3Theme.applyType(this, 13f, bold = true, color = scheme.onSurface)
                maxLines = 1
            }
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (i < milestoneOptions.lastIndex) setMargins(0, 0, dp(8), 0) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedLastCompleted = kmVal
                    refreshMsChips()
                }
            }
            chip.addView(tv)
            msButtons.add(Triple(kmVal, chip, tv))
            msRow.addView(chip)
        }
        refreshMsChips()
        msScroll.addView(msRow)
        content.addView(msScroll)

        // Data base (última revisão ou compra 0 km)
        content.addView(TextView(this).apply {
            text = "Data da última revisão (ou retirada 0 km)"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(6))
        })
        var selectedDateMs = snap.baseDateMs
        val dateText = TextView(this).apply {
            text = if (selectedDateMs > 0L) maintenanceManager.formatDateShort(selectedDateMs) else "Toque para escolher a data"
            M3Theme.applyType(this, 14f, bold = true, color = scheme.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val datePickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = scheme.surfaceContainer,
                radiusPx = dp(12).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary,
                normalStrokeColor = scheme.outlineVariant,
                normalStrokeWidthPx = dp(1)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val cal = Calendar.getInstance().apply {
                    if (selectedDateMs > 0L) timeInMillis = selectedDateMs
                }
                DatePickerDialog(
                    this@MainActivity,
                    { _, y, m, d ->
                        val picked = Calendar.getInstance().apply {
                            set(y, m, d, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        selectedDateMs = picked.timeInMillis
                        dateText.text = maintenanceManager.formatDateShort(selectedDateMs)
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                }.show()
            }
        }
        datePickerRow.addView(dateText)
        val dateIcon = M3Theme.makeTintedIcon(this, R.drawable.ic_edit, 18, scheme.onSurfaceVariant)
        datePickerRow.addView(dateIcon)
        content.addView(datePickerRow)

        // Prazo da 1ª revisão (1 mês vs 3 meses)
        content.addView(TextView(this).apply {
            text = "Prazo da 1ª revisão (1.000 km) no manual"
            M3Theme.applyRole(this, M3TypeRole.LABEL_MEDIUM, scheme.onSurfaceVariant)
            setPadding(0, 0, 0, dp(6))
        })
        var selectedFirstMonths = snap.firstServiceMonths
        val firstRow = createTwoColRow(bottomMarginDp = 0)
        val firstOne = createSegmentedChoiceChip("1 mês (padrão)", isLeft = true) {}
        val firstThree = createSegmentedChoiceChip("3 meses (novos)", isLeft = false) {}
        val syncFirstChips = {
            val apply = { chip: LinearLayout, tv: TextView, sel: Boolean ->
                chip.background = M3Theme.interactiveSurface(
                    context = this,
                    bgColor = if (sel) scheme.secondaryContainer else scheme.surfaceContainer,
                    radiusPx = dp(999).toFloat(),
                    rippleColor = scheme.rippleOnSurface,
                    focusStrokeColor = scheme.primary,
                    normalStrokeColor = if (sel) 0 else scheme.outlineVariant,
                    normalStrokeWidthPx = if (sel) 0 else dp(1)
                )
                tv.setTextColor(if (sel) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
            }
            apply(firstOne.first, firstOne.second, selectedFirstMonths == 1)
            apply(firstThree.first, firstThree.second, selectedFirstMonths == 3)
        }
        firstOne.first.setOnClickListener {
            selectedFirstMonths = 1
            syncFirstChips()
        }
        firstThree.first.setOnClickListener {
            selectedFirstMonths = 3
            syncFirstChips()
        }
        syncFirstChips()
        firstRow.addView(firstOne.first)
        firstRow.addView(firstThree.first)
        content.addView(firstRow)

        val handle = createM3Dialog(
            title = "Ajustar odômetro e revisão",
            subtitle = "Sincronize com o painel real da moto para corrigir a estimativa do GPS.",
            content = scroll,
            neutralText = if (selectedDateMs > 0L) "Limpar data" else null,
            onNeutral = if (selectedDateMs > 0L) { dlg ->
                selectedDateMs = 0L
                dateText.text = "Toque para escolher a data"
                toast("Data removida — clique em Salvar para confirmar")
            } else null,
            confirmText = "Salvar"
        ) { dlg ->
            val raw = kmEdit.text?.toString()?.trim()?.replace(",", ".") ?: "0"
            val parsedKm = raw.toDoubleOrNull()
            if (parsedKm == null || parsedKm < 0.0) {
                toast("Informe uma quilometragem válida")
                return@createM3Dialog
            }
            maintenanceManager.configureOdometerAndSchedule(
                odometerKm = parsedKm,
                lastCompletedMilestoneKm = selectedLastCompleted,
                baseDateMs = selectedDateMs,
                firstServiceMonths = selectedFirstMonths
            )
            toast("Odômetro atualizado para ${maintenanceManager.formatDecimalKm(parsedKm)} km")
            dlg.dismiss()
        }
        handle.dialog.show()
    }

    private fun openMilestoneActionDialog(milestone: ServiceMilestone) {
        val body = TextView(this).apply {
            text = if (milestone.isCompleted) {
                "A ${milestone.order}ª revisão (${maintenanceManager.formatIntKm(milestone.km)} km) já está marcada como concluída.\n\nItens: ${milestone.summary}"
            } else {
                "Deseja registrar a ${milestone.order}ª revisão (${maintenanceManager.formatIntKm(milestone.km)} km) como feita hoje? O prazo de 6 meses passará a contar a partir de hoje.\n\nItens: ${milestone.summary}"
            }
            M3Theme.applyRole(this, M3TypeRole.BODY_MEDIUM, scheme.onSurface)
            setLineSpacing(0f, 1.3f)
        }

        val handle = createM3Dialog(
            title = "${milestone.order}ª revisão • ${maintenanceManager.formatIntKm(milestone.km)} km",
            subtitle = "Regra Bajaj: ${milestone.timeRuleLabel}",
            content = body,
            cancelText = "Fechar",
            confirmText = if (milestone.isCompleted) "Desmarcar" else "Marcar como feita"
        ) { dlg ->
            if (milestone.isCompleted) {
                maintenanceManager.toggleMilestoneCompleted(milestone.km)
                toast("Revisão de ${maintenanceManager.formatIntKm(milestone.km)} km desmarcada")
            } else {
                maintenanceManager.registerServiceCompleted(milestone.km, System.currentTimeMillis())
                toast("Revisão de ${maintenanceManager.formatIntKm(milestone.km)} km registrada!")
            }
            dlg.dismiss()
        }
        handle.dialog.show()
    }

    // =========================================================================
    // 7. NAVEGAÇÃO M3 DOCKADA (3 MENUS: Cockpit, Ações e Perfil)
    // =========================================================================
    private fun buildDockedBottomNav(parent: FrameLayout) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(scheme.surfaceContainer)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
        }

        wrapper.addView(View(this).apply {
            setBackgroundColor(scheme.outlineVariant)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            weightSum = 3.0f
            minimumHeight = dp(76)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        barRow.addView(createNavItem("Cockpit", R.drawable.ic_nav_dashboard, 0, isRail = false))
        barRow.addView(createNavItem("Ações", R.drawable.ic_nav_settings, 1, isRail = false))
        barRow.addView(createNavItem("Perfil", R.drawable.ic_person, 2, isRail = false))
        wrapper.addView(barRow)

        bottomNavWrapper = wrapper
        parent.addView(wrapper)
    }

    private fun createNavItem(title: String, iconRes: Int, index: Int, isRail: Boolean): LinearLayout {
        val selected = index == activeTabIndex
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(56)
            minimumWidth = dp(56)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = if (isRail) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(4), 0, dp(10))
                }
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = dp(16).toFloat(),
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = title
        }
        navTabContainers.add(container)

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (selected) M3Theme.roundedSurface(scheme.secondaryContainer, dp(16).toFloat()) else null
            layoutParams = LinearLayout.LayoutParams(dp(if (isRail) 56 else 64), dp(32))
        }
        navTabPills.add(pill)

        val icon = M3Theme.makeTintedIcon(
            this,
            iconRes,
            22,
            if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
        )
        navTabIcons.add(icon)
        pill.addView(icon)
        container.addView(pill)

        val label = TextView(this).apply {
            text = title
            M3Theme.applyRole(
                this,
                M3TypeRole.LABEL_MEDIUM,
                if (selected) scheme.onSurface else scheme.onSurfaceVariant
            )
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(2), dp(4), dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        navTabLabels.add(label)
        container.addView(label)

        container.setOnClickListener {
            if (activeTabIndex != index) {
                switchTab(index)
            }
        }
        return container
    }

    private fun viewForTabIndex(index: Int): LinearLayout {
        return when (index) {
            0 -> tabCockpitContent
            1 -> tabActionsContent
            else -> tabProfileContent
        }
    }

    private fun switchTab(newIndex: Int) {
        val currentView = viewForTabIndex(activeTabIndex)
        val nextView = viewForTabIndex(newIndex)

        activeTabIndex = newIndex
        applyTabSelectionUi(newIndex, animate = animationsEnabled)

        if (!isWideLayout && ::hudClusterCard.isInitialized) {
            hudClusterCard.visibility = if (newIndex == 2) View.GONE else View.VISIBLE
        }
        if (isWideLayout) {
            wideRightScrollView?.scrollTo(0, 0)
        } else if (::compactScrollView.isInitialized) {
            compactScrollView.scrollTo(0, 0)
        }

        currentView.animate().cancel()
        nextView.animate().cancel()

        if (!animationsEnabled) {
            currentView.visibility = View.GONE
            currentView.alpha = 1f
            currentView.translationY = 0f
            nextView.visibility = View.VISIBLE
            nextView.alpha = 1f
            nextView.translationY = 0f
            return
        }

        currentView.animate()
            .alpha(0f)
            .setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                currentView.visibility = View.GONE
                currentView.alpha = 1f

                nextView.visibility = View.VISIBLE
                nextView.alpha = 0f
                nextView.translationY = dp(8).toFloat()
                nextView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(170)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun applyTabSelectionUi(selectedIndex: Int, animate: Boolean) {
        for (i in navTabLabels.indices) {
            val selected = (i == selectedIndex)
            navTabContainers[i].isSelected = selected
            navTabLabels[i].setTextColor(if (selected) scheme.onSurface else scheme.onSurfaceVariant)
            M3Theme.applyType(navTabLabels[i], 12f, bold = selected)
            navTabIcons[i].imageTintList = ColorStateList.valueOf(
                if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
            )

            val pill = navTabPills[i]
            if (selected) {
                pill.background = M3Theme.roundedSurface(scheme.secondaryContainer, dp(16).toFloat())
                if (animate) {
                    pill.scaleX = 0.86f
                    pill.animate()
                        .scaleX(1.0f)
                        .setDuration(180)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                } else {
                    pill.scaleX = 1.0f
                }
            } else {
                pill.background = null
                pill.scaleX = 1.0f
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun applyTactileFeedback(view: View) {
        if (!animationsEnabled) return
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(80)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }

    // =========================================================================
    // 8. CALLBACKS BLE & ATUALIZAÇÃO DO CLUSTER
    // =========================================================================
    private fun setupBleCallbacks() {
        bleManager.onStatusChanged = { msg ->
            runOnUiThread {
                updateStatusUi(msg)
                refreshPinnedBikeUi()
            }
        }

        bleManager.onScanStateChanged = { scanning ->
            runOnUiThread {
                updateScanButtonUi(scanning)
            }
        }

        bleManager.onDataRefreshed = { batt, sig, callInfo ->
            runOnUiThread {
                updateTelemetryUi(batt, sig, callInfo)
            }
        }

        bleManager.onNavigationUpdated = { snapshot ->
            runOnUiThread {
                updateClusterUi(snapshot)
            }
        }

        bleManager.onDiagnosticsUpdated = {
            runOnUiThread {
                refreshDiagnosticsUi()
            }
        }

        bleManager.onConnectionMetaChanged = {
            runOnUiThread {
                refreshPinnedBikeUi()
            }
        }

        maintenanceManager.onSnapshotUpdated = { snapshot ->
            runOnUiThread {
                refreshMaintenanceUi(snapshot)
            }
        }
    }

    private fun updateScanButtonUi(scanning: Boolean) {
        if (!::scanDevicesButtonLabel.isInitialized) return
        scanDevicesButtonLabel.text = if (scanning) "Buscando painel..." else "Buscar painel"
    }

    private fun updateStatusUi(msg: String) {
        val isConnected = bleManager.isConnected()
        val isError = !isConnected && (
            msg.contains("Falha", ignoreCase = true) ||
                msg.contains("desativado", ignoreCase = true) ||
                msg.contains("Permiss", ignoreCase = true)
            )
        val isActiveSearching = !isConnected && !isError && (
            bleManager.isScanning() ||
                bleManager.isConnecting() ||
                msg.contains("Buscando", ignoreCase = true) ||
                msg.contains("Conectando", ignoreCase = true) ||
                msg.contains("Reconectando", ignoreCase = true) ||
                msg.contains("Preparando", ignoreCase = true)
            )
        val isWaiting = !isConnected && !isError && !isActiveSearching && (
            bleManager.isPassiveWaiting() ||
                msg.contains("Aguardando", ignoreCase = true)
            )

        val (shortText, bgColor, fgColor, iconRes) = when {
            isConnected -> Quad("Conectado", scheme.tertiaryContainer, scheme.onTertiaryContainer, R.drawable.ic_bluetooth)
            isError -> Quad(
                if (msg.contains("Permiss", ignoreCase = true)) "Permissões" else "Offline",
                scheme.errorContainer,
                scheme.onErrorContainer,
                R.drawable.ic_call_end
            )
            isActiveSearching -> Quad(
                when {
                    msg.contains("Reconect", ignoreCase = true) -> "Reconectando"
                    msg.contains("Conectando", ignoreCase = true) || msg.contains("Preparando", ignoreCase = true) -> "Conectando"
                    else -> "Buscando"
                },
                scheme.primaryContainer,
                scheme.onPrimaryContainer,
                R.drawable.ic_bluetooth
            )
            isWaiting -> Quad("Aguardando", scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.drawable.ic_bluetooth)
            else -> Quad("Desconectado", scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.drawable.ic_bluetooth)
        }

        val visualKey = "$shortText|$bgColor|$fgColor|$iconRes"
        if (visualKey == lastStatusVisualKey) return
        lastStatusVisualKey = visualKey

        statusBadge.text = shortText
        statusBadge.setTextColor(fgColor)
        statusChipIcon.setImageResource(iconRes)
        statusChipIcon.imageTintList = ColorStateList.valueOf(fgColor)
        statusChipContainer.background = M3Theme.interactiveSurface(
            context = this,
            bgColor = bgColor,
            radiusPx = dp(999).toFloat(),
            rippleColor = scheme.rippleOnSurface,
            focusStrokeColor = scheme.primary,
            normalStrokeColor = if (isConnected || isActiveSearching || isError) 0 else scheme.outlineVariant,
            normalStrokeWidthPx = if (isConnected || isActiveSearching || isError) 0 else dp(1)
        )
    }

    private fun updateTelemetryUi(batt: Int, sig: Int, callInfo: String) {
        val clampedBatt = batt.coerceIn(0, 100)
        val clampedSig = sig.coerceIn(0, 4)
        val telemetryKey = "$clampedBatt|$clampedSig|$callInfo"
        if (telemetryKey == lastTelemetryUiKey) return
        lastTelemetryUiKey = telemetryKey

        battValueText.text = "$clampedBatt%"
        (battFillView.layoutParams as LinearLayout.LayoutParams).weight = clampedBatt.toFloat()
        (battEmptyView.layoutParams as LinearLayout.LayoutParams).weight = (100 - clampedBatt).toFloat()
        battFillView.requestLayout()

        val battColor = when {
            clampedBatt > 30 -> scheme.primary
            clampedBatt > 15 -> scheme.warning
            else -> scheme.error
        }
        battFillView.background = M3Theme.roundedSurface(battColor, dp(2).toFloat())
        battIcon.imageTintList = ColorStateList.valueOf(
            if (clampedBatt <= 30) battColor else scheme.onSurfaceVariant
        )

        signalValueText.text = "$clampedSig/4"
        signalIcon.imageTintList = ColorStateList.valueOf(
            if (clampedSig <= 1) scheme.warning else scheme.onSurfaceVariant
        )
        for (i in signalBars.indices) {
            val isActive = i < clampedSig
            signalBars[i].background = M3Theme.roundedSurface(
                if (isActive) scheme.primary else scheme.outlineVariant,
                dp(1).toFloat()
            )
        }

        val isBusy = callInfo.isNotEmpty() && !callInfo.equals("Livre", ignoreCase = true)
        callIcon.imageTintList = ColorStateList.valueOf(
            if (isBusy) scheme.error else scheme.onSurfaceVariant
        )
        callValueText.text = if (isBusy) callInfo else "Livre"
        callValueText.setTextColor(if (isBusy) scheme.error else scheme.onSurface)
        callStatusDot.background = M3Theme.roundedSurface(
            if (isBusy) scheme.error else scheme.outline,
            dp(4).toFloat()
        )

        if (isBusy) {
            hudCallerNameText.text = "Chamada: $callInfo"
            if (hudCallBanner.visibility != View.VISIBLE) {
                hudCallBanner.visibility = View.VISIBLE
                if (animationsEnabled) {
                    hudCallBanner.alpha = 0f
                    hudCallBanner.animate().alpha(1f).setDuration(180).start()
                } else {
                    hudCallBanner.alpha = 1f
                }
            }
        } else if (hudCallBanner.visibility == View.VISIBLE) {
            if (animationsEnabled) {
                hudCallBanner.animate().alpha(0f).setDuration(150).withEndAction {
                    hudCallBanner.visibility = View.GONE
                }.start()
            } else {
                hudCallBanner.visibility = View.GONE
            }
        }
    }

    private fun updateClusterUi(snapshot: NavigationSnapshot) {
        arrowBlinkAnimator?.cancel()

        if (!snapshot.isActive) {
            hudClusterCard.background = M3Theme.roundedSurface(
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(M3Shape.EXTRA_LARGE_DP).toFloat()
            )
            hudClusterTag.setTextColor(scheme.onSurfaceVariant)
            hudArrowIcon.setImageResource(R.drawable.ic_arrow_straight)
            hudArrowIcon.imageTintList = ColorStateList.valueOf(scheme.onSurfaceVariant)
            hudArrowIcon.alpha = 1.0f
            hudDistanceValue.text = "--"
            hudDistanceValue.setTextColor(scheme.onSurface)
            hudDistanceUnit.text = ""
            hudDistanceUnit.setTextColor(scheme.primary)
            hudManeuverSubtext.text = "Sem rota ativa"
            hudManeuverSubtext.setTextColor(scheme.onSurfaceVariant)
            hudStreetIcon.imageTintList = ColorStateList.valueOf(scheme.onSurfaceVariant)
            hudStreetText.text = "Aguardando rota no Google Maps"
            hudStreetText.setTextColor(scheme.onSurface)

            hudSourceBadge.background = M3Theme.roundedSurface(scheme.surfaceContainerHighest, dp(M3Shape.FULL_DP).toFloat())
            hudSourceBadgeIcon.setImageResource(R.drawable.ic_map)
            hudSourceBadgeIcon.imageTintList = ColorStateList.valueOf(scheme.onSurfaceVariant)
            hudSourceBadgeText.text = "Inativo"
            hudSourceBadgeText.setTextColor(scheme.onSurfaceVariant)
            return
        }

        val distanceMeters = snapshot.distanceMeters
        if (distanceMeters >= 1000) {
            val km = distanceMeters / 1000.0
            hudDistanceValue.text = String.format(Locale.US, "%.1f", km)
            hudDistanceUnit.text = " km"
        } else {
            hudDistanceValue.text = distanceMeters.toString()
            hudDistanceUnit.text = " m"
        }

        val cleanStreet = snapshot.streetText.ifEmpty { "Rota em andamento" }
        hudStreetText.text = if (snapshot.totalDistanceMeters > distanceMeters && snapshot.totalDistanceMeters > 0) {
            val totalStr = if (snapshot.totalDistanceMeters >= 1000) {
                String.format(Locale.US, "%.1f km", snapshot.totalDistanceMeters / 1000.0)
            } else {
                "${snapshot.totalDistanceMeters} m"
            }
            "Faltam $totalStr • $cleanStreet"
        } else {
            cleanStreet
        }

        val isClockwiseRoundabout = snapshot.maneuverCode == BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID ||
            snapshot.maneuverCode == BajajProtocol.ARROW_ROUNDABOUT_RIGHT_BLINK
        val visualPos = if (snapshot.roundaboutExit in 1..7) {
            if (isClockwiseRoundabout) 8 - snapshot.roundaboutExit else snapshot.roundaboutExit
        } else 4

        val roundaboutDirLabel = when (visualPos) {
            1 -> getString(R.string.roundabout_dir_1)
            2 -> getString(R.string.roundabout_dir_2)
            3 -> getString(R.string.roundabout_dir_3)
            4 -> getString(R.string.roundabout_dir_4)
            5 -> getString(R.string.roundabout_dir_5)
            6 -> getString(R.string.roundabout_dir_6)
            7 -> getString(R.string.roundabout_dir_7)
            else -> ""
        }
        val exitSuffix = if (snapshot.roundaboutExit in 1..7 && roundaboutDirLabel.isNotBlank()) " • $roundaboutDirLabel" else ""

        val roundaboutDrawable = when (visualPos) {
            1 -> R.drawable.ic_arrow_roundabout_pos1
            2 -> R.drawable.ic_arrow_roundabout_pos2
            3 -> R.drawable.ic_arrow_roundabout_pos3
            4 -> R.drawable.ic_arrow_roundabout_pos4
            5 -> R.drawable.ic_arrow_roundabout_pos5
            6 -> R.drawable.ic_arrow_roundabout_pos6
            7 -> R.drawable.ic_arrow_roundabout_pos7
            else -> R.drawable.ic_arrow_roundabout_pos4
        }
        val (iconRes, isBlink, description) = when (snapshot.maneuverCode) {
            BajajProtocol.ARROW_STRAIGHT_SOLID -> Triple(R.drawable.ic_arrow_straight, false, "Siga em linha reta")
            BajajProtocol.ARROW_STRAIGHT_BLINK -> Triple(R.drawable.ic_arrow_straight, true, "Atenção: siga em frente")
            BajajProtocol.ARROW_RIGHT_SOLID -> Triple(R.drawable.ic_arrow_right, false, "Vire à direita")
            BajajProtocol.ARROW_RIGHT_BLINK -> Triple(R.drawable.ic_arrow_right, true, "Entre à direita agora")
            BajajProtocol.ARROW_LEFT_SOLID -> Triple(R.drawable.ic_arrow_left, false, "Vire à esquerda")
            BajajProtocol.ARROW_LEFT_BLINK -> Triple(R.drawable.ic_arrow_left, true, "Entre à esquerda agora")
            BajajProtocol.ARROW_SLIGHT_LEFT_SOLID -> Triple(R.drawable.ic_arrow_slight_left, false, "Curva leve à esquerda")
            BajajProtocol.ARROW_SLIGHT_LEFT_BLINK -> Triple(R.drawable.ic_arrow_slight_left, true, "Entre leve à esquerda")
            BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID -> Triple(R.drawable.ic_arrow_slight_right, false, "Curva leve à direita")
            BajajProtocol.ARROW_SLIGHT_RIGHT_BLINK -> Triple(R.drawable.ic_arrow_slight_right, true, "Entre leve à direita")
            BajajProtocol.ARROW_FORK_LEFT_SOLID -> Triple(R.drawable.ic_arrow_fork_left, false, "Bifurcação à esquerda")
            BajajProtocol.ARROW_FORK_LEFT_BLINK -> Triple(R.drawable.ic_arrow_fork_left, true, "Pegue a bifurcação à esquerda")
            BajajProtocol.ARROW_FORK_RIGHT_SOLID -> Triple(R.drawable.ic_arrow_fork_right, false, "Bifurcação à direita")
            BajajProtocol.ARROW_FORK_RIGHT_BLINK -> Triple(R.drawable.ic_arrow_fork_right, true, "Pegue a bifurcação à direita")
            BajajProtocol.ARROW_RAMP_LEFT_SOLID -> Triple(R.drawable.ic_arrow_ramp_left, false, "Alça de acesso à esquerda")
            BajajProtocol.ARROW_RAMP_LEFT_BLINK -> Triple(R.drawable.ic_arrow_ramp_left, true, "Entre na alça à esquerda")
            BajajProtocol.ARROW_RAMP_RIGHT_SOLID -> Triple(R.drawable.ic_arrow_ramp_right, false, "Alça / saída à direita")
            BajajProtocol.ARROW_RAMP_RIGHT_BLINK -> Triple(R.drawable.ic_arrow_ramp_right, true, "Entre na saída à direita")
            BajajProtocol.ARROW_KEEP_LEFT_SOLID -> Triple(R.drawable.ic_arrow_slight_left, false, "Mantenha-se à esquerda")
            BajajProtocol.ARROW_KEEP_LEFT_BLINK -> Triple(R.drawable.ic_arrow_slight_left, true, "Mantenha-se à esquerda")
            BajajProtocol.ARROW_KEEP_RIGHT_SOLID -> Triple(R.drawable.ic_arrow_slight_right, false, "Mantenha-se à direita")
            BajajProtocol.ARROW_KEEP_RIGHT_BLINK -> Triple(R.drawable.ic_arrow_slight_right, true, "Mantenha-se à direita")
            BajajProtocol.ARROW_SHARP_LEFT_SOLID -> Triple(R.drawable.ic_arrow_sharp_left, false, "Curva acentuada à esquerda")
            BajajProtocol.ARROW_SHARP_LEFT_BLINK -> Triple(R.drawable.ic_arrow_sharp_left, true, "Curva acentuada à esquerda")
            BajajProtocol.ARROW_SHARP_RIGHT_SOLID -> Triple(R.drawable.ic_arrow_sharp_right, false, "Curva acentuada à direita")
            BajajProtocol.ARROW_SHARP_RIGHT_BLINK -> Triple(R.drawable.ic_arrow_sharp_right, true, "Curva acentuada à direita")
            BajajProtocol.ARROW_MERGE -> Triple(R.drawable.ic_arrow_merge, false, "Convergência na via")
            BajajProtocol.ARROW_UTURN_LEFT_SOLID -> Triple(R.drawable.ic_arrow_uturn, false, "Retorno à esquerda")
            BajajProtocol.ARROW_UTURN_LEFT_BLINK -> Triple(R.drawable.ic_arrow_uturn, true, "Faça o retorno à esquerda")
            BajajProtocol.ARROW_UTURN_RIGHT_SOLID -> Triple(R.drawable.ic_arrow_uturn_right, false, "Retorno à direita")
            BajajProtocol.ARROW_UTURN_RIGHT_BLINK -> Triple(R.drawable.ic_arrow_uturn_right, true, "Faça o retorno à direita")
            BajajProtocol.ARROW_ROUNDABOUT_LEFT_SOLID -> Triple(roundaboutDrawable, false, "${getString(R.string.maneuver_roundabout_right)}$exitSuffix")
            BajajProtocol.ARROW_ROUNDABOUT_LEFT_BLINK -> Triple(roundaboutDrawable, true, "${getString(R.string.maneuver_roundabout_right)}$exitSuffix")
            BajajProtocol.ARROW_ROUNDABOUT_RIGHT_SOLID -> Triple(roundaboutDrawable, false, "${getString(R.string.maneuver_roundabout_left)}$exitSuffix")
            BajajProtocol.ARROW_ROUNDABOUT_RIGHT_BLINK -> Triple(roundaboutDrawable, true, "${getString(R.string.maneuver_roundabout_left)}$exitSuffix")
            BajajProtocol.ARROW_FERRY_SOLID -> Triple(R.drawable.ic_ferry, false, "Travessia de balsa")
            BajajProtocol.ARROW_FERRY_BLINK -> Triple(R.drawable.ic_ferry, true, "Embarque na balsa")
            BajajProtocol.ARROW_FERRY_TRAIN_SOLID -> Triple(R.drawable.ic_train, false, "Balsa / trem")
            BajajProtocol.ARROW_WRONG_WAY -> Triple(R.drawable.ic_wrong_way, false, "Recalculando / contramão")
            BajajProtocol.ARROW_DESTINATION_SOLID -> Triple(R.drawable.ic_flag_destination, false, "Você chegou ao destino")
            BajajProtocol.ARROW_DESTINATION_BLINK -> Triple(R.drawable.ic_flag_destination, true, "Chegando ao destino")
            else -> Triple(R.drawable.ic_arrow_straight, false, "Em deslocamento")
        }

        hudArrowIcon.setImageResource(iconRes)
        if (snapshot.isArrived) {
            hudClusterCard.background = M3Theme.roundedSurface(
                bgColor = scheme.tertiaryContainer,
                radiusPx = dp(M3Shape.EXTRA_LARGE_DP).toFloat()
            )
            hudClusterTag.setTextColor(scheme.onTertiaryContainer)
            hudArrowIcon.imageTintList = ColorStateList.valueOf(scheme.onTertiaryContainer)
            hudDistanceValue.setTextColor(scheme.onTertiaryContainer)
            hudDistanceUnit.setTextColor(scheme.onTertiaryContainer)
            hudManeuverSubtext.setTextColor(scheme.onTertiaryContainer)
            hudStreetIcon.imageTintList = ColorStateList.valueOf(scheme.onTertiaryContainer)
            hudStreetText.setTextColor(scheme.onTertiaryContainer)
            hudManeuverSubtext.text = "Você chegou ao destino"
        } else {
            hudClusterCard.background = M3Theme.roundedSurface(
                bgColor = scheme.surfaceContainerHigh,
                radiusPx = dp(M3Shape.EXTRA_LARGE_DP).toFloat()
            )
            hudClusterTag.setTextColor(scheme.onSurfaceVariant)
            val tintColor = when {
                snapshot.maneuverCode == BajajProtocol.ARROW_WRONG_WAY -> scheme.error
                isBlink -> scheme.warning
                else -> scheme.primary
            }
            hudArrowIcon.imageTintList = ColorStateList.valueOf(tintColor)
            hudDistanceValue.setTextColor(scheme.onSurface)
            hudDistanceUnit.setTextColor(scheme.primary)
            hudManeuverSubtext.setTextColor(scheme.onSurfaceVariant)
            hudStreetIcon.imageTintList = ColorStateList.valueOf(scheme.onSurfaceVariant)
            hudStreetText.setTextColor(scheme.onSurface)
            hudManeuverSubtext.text = description
        }

        val isMaps = snapshot.sourceLabel == "Google Maps" || snapshot.sourceLabel == "Maps + GPS"
        val badgeBg = when {
            snapshot.isArrived -> scheme.surface
            isMaps -> scheme.tertiaryContainer
            else -> scheme.primaryContainer
        }
        val badgeFg = when {
            snapshot.isArrived -> scheme.onSurface
            isMaps -> scheme.onTertiaryContainer
            else -> scheme.onPrimaryContainer
        }
        val badgeIcon = if (isMaps) R.drawable.ic_map else R.drawable.ic_nav_navigation
        hudSourceBadge.background = M3Theme.roundedSurface(badgeBg, dp(M3Shape.FULL_DP).toFloat())
        hudSourceBadgeIcon.setImageResource(badgeIcon)
        hudSourceBadgeIcon.imageTintList = ColorStateList.valueOf(badgeFg)
        hudSourceBadgeText.text = snapshot.sourceLabel
        hudSourceBadgeText.setTextColor(badgeFg)

        if (isBlink && animationsEnabled) {
            arrowBlinkAnimator = ObjectAnimator.ofFloat(hudArrowIcon, "alpha", 1f, 0.3f).apply {
                duration = 420
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            hudArrowIcon.alpha = 1.0f
        }
    }

    // =========================================================================
    // 9. FÁBRICAS DE COMPONENTES MATERIAL 3
    // =========================================================================
    private fun createM3Button(
        btnText: String,
        iconRes: Int,
        bgColor: Int,
        contentColor: Int,
        rippleColor: Int,
        strokeColor: Int = 0,
        bottomMarginDp: Int = 12,
        onClick: () -> Unit
    ): LinearLayout {
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minimumHeight = dp(48)
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = bgColor,
                radiusPx = dp(999).toFloat(),
                rippleColor = rippleColor,
                focusStrokeColor = scheme.primary,
                normalStrokeColor = strokeColor,
                normalStrokeWidthPx = if (strokeColor != 0) dp(1) else 0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(bottomMarginDp)) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        applyTactileFeedback(btn)

        val icon = M3Theme.makeTintedIcon(this, iconRes, 18, contentColor)
        (icon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(8), 0)
        btn.addView(icon)

        val tv = TextView(this).apply {
            text = btnText
            M3Theme.applyRole(this, M3TypeRole.LABEL_LARGE, contentColor)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        btn.addView(tv)
        return btn
    }

    private fun createListActionRow(
        title: String,
        subtitle: String,
        iconRes: Int,
        iconTint: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = M3Theme.interactiveSurface(
                context = this@MainActivity,
                bgColor = Color.TRANSPARENT,
                radiusPx = 0f,
                rippleColor = scheme.rippleOnSurface,
                focusStrokeColor = scheme.primary
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val icon = M3Theme.makeTintedIcon(this, iconRes, 22, iconTint)
        (icon.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(14), 0)
        row.addView(icon)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        val titleTv = TextView(this).apply {
            text = title
            M3Theme.applyType(this, 15f, bold = true, color = scheme.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleTv)

        val subTv = TextView(this).apply {
            text = subtitle
            M3Theme.applyType(this, 13f, bold = false, color = scheme.onSurfaceVariant)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(subTv)
        row.addView(textCol)

        val chevron = M3Theme.makeTintedIcon(this, R.drawable.ic_arrow_right, 18, scheme.onSurfaceVariant)
        row.addView(chevron)
        return row
    }

    private fun createTwoColRow(bottomMarginDp: Int = 8): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2.0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(bottomMarginDp)) }
        }
    }

    private fun addSectionHeader(parent: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            M3Theme.applyRole(this, M3TypeRole.TITLE_SMALL, scheme.onSurfaceVariant)
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }
        M3Theme.markAccessibilityHeading(tv)
        parent.addView(tv)
    }

    private fun createHorizontalHairline(): View {
        return View(this).apply {
            setBackgroundColor(scheme.outlineVariant)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
        }
    }

    private fun createVerticalHairline(): View {
        return View(this).apply {
            setBackgroundColor(scheme.outlineVariant)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(40))
        }
    }

    private fun dp(v: Int): Int = M3Theme.dp(this, v)

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    private fun promptBatteryOptimizationOnce() {
        if (preferences.hasAskedBatteryOptimization()) return
        preferences.markAskedBatteryOptimization()
        openBatteryOptimizationSettings()
    }

    private fun openBatteryOptimizationSettings(openDetailsIfAlreadyIgnored: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else if (openDetailsIfAlreadyIgnored) {
                    toast("Otimização de bateria já desativada")
                    openAppDetailsSettings()
                } else {
                    toast("Otimização de bateria já desativada")
                }
            } catch (_: Exception) {
                openAppDetailsSettings()
            }
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            toast("Não foi possível abrir configurações do app")
        }
    }

    override fun onDestroy() {
        arrowBlinkAnimator?.cancel()
        bleManager.stopScan()
        bleManager.onStatusChanged = null
        bleManager.onScanStateChanged = null
        bleManager.onDataRefreshed = null
        bleManager.onNavigationUpdated = null
        bleManager.onDiagnosticsUpdated = null
        bleManager.onConnectionMetaChanged = null
        maintenanceManager.onSnapshotUpdated = null
        super.onDestroy()
    }

    // =========================================================================
    // 10. PERMISSÕES E BLUETOOTH SCANNER
    // =========================================================================
    private fun checkBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            val connect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            return scan == PackageManager.PERMISSION_GRANTED && connect == PackageManager.PERMISSION_GRANTED
        }
        val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        return loc == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS
            )
        }
        requestPermissions(permissions, 101)
    }

    private fun requestCallAndContactPermissions() {
        requestPermissions(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS
            ),
            102
        )
    }

    private fun requestSmsAndCallControlPermissions() {
        requestPermissions(
            arrayOf(
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS
            ),
            103
        )
    }

    private fun requestOdometerLocationPermission() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(perms, 104)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshDiagnosticsUi()

        if (requestCode == 104) {
            maintenanceManager.refreshTrackingAndNotify()
            refreshMaintenanceUi(maintenanceManager.getSnapshot())
            if (!maintenanceManager.hasLocationPermission()) {
                toast("Permissão de localização necessária para o odômetro GPS")
            }
            return
        }

        if (requestCode == 102) {
            if (hasCallAndContactPermissions()) {
                toast("Permissões de chamadas e contatos concedidas")
            } else {
                toast("Libere Telefone, Registro de chamadas e Contatos nas configurações do app")
                openAppDetailsSettings()
            }
            return
        }

        if (requestCode == 103) {
            if (hasSmsAndCallControlPermissions()) {
                toast("Permissões de atendimento e SMS concedidas")
            } else {
                toast("Libere Telefone e SMS nas configurações do app")
                openAppDetailsSettings()
            }
            return
        }

        if (checkBlePermissions()) {
            maintenanceManager.refreshTrackingAndNotify()
            if (!bleManager.tryAutoConnectPinned(preferPassiveAutoConnect = true)) {
                startScan()
            }
        } else {
            updateStatusUi("Permissões necessárias")
            showDeviceEmptyState(
                message = "Permissões Bluetooth são necessárias para localizar o painel da moto.",
                actionLabel = "Conceder permissões"
            ) {
                requestBlePermissions()
            }
        }
    }

    private fun startScan() {
        discoveredDevices.clear()
        showDeviceEmptyState(
            message = "Procurando painel da moto por perto..."
        )

        bleManager.startScan { device ->
            val addr = device.address ?: return@startScan
            if (!discoveredDevices.containsKey(addr)) {
                discoveredDevices[addr] = device
                runOnUiThread {
                    refreshDeviceListUi()
                    val name = device.name ?: ""
                    val isPinnedMatch = preferences.isPinned(addr)
                    val isDominar = name.contains("DOMINAR", ignoreCase = true) ||
                        name.contains("BAJAJ", ignoreCase = true)

                    if (!bleManager.isConnected() && !bleManager.isConnecting() &&
                        (isPinnedMatch || (preferences.getPinnedAddress() == null && isDominar))
                    ) {
                        bleManager.connect(device)
                    }
                }
            }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
