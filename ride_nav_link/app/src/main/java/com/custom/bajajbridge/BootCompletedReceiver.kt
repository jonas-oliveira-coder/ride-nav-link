package com.custom.bajajbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BajajBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val prefs = AppPreferences(context)
        if (!prefs.isAutoStartEnabled()) {
            Log.d(TAG, "Auto-start desativado nas preferências; ignorando $action")
            return
        }

        val pinnedAddr = prefs.getPinnedAddress()
        val pinnedDisplay = prefs.getDisplayName(pinnedAddr, prefs.getPinnedName())
        val statusText = if (pinnedAddr != null) {
            "Reconectando automaticamente a $pinnedDisplay..."
        } else {
            "Ativo em segundo plano • aguardando painel"
        }

        try {
            BajajForegroundService.startService(context.applicationContext, statusText)
            MapsNotificationListener.requestRebindIfEnabled(context.applicationContext)
            Log.d(TAG, "Serviço iniciado automaticamente após $action")
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível iniciar ForegroundService após $action: ${e.message}")
        }
    }
}
