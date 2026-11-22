package com.custom.ridenavlink

import android.app.Application
import android.content.Context

class BajajBridgeApp : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var maintenanceManager: MaintenanceManager
        private set

    lateinit var bleManager: BajajBleManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = AppPreferences(this)
        maintenanceManager = MaintenanceManager(this, preferences)
        bleManager = BajajBleManager(this, preferences, maintenanceManager)
    }

    companion object {
        @Volatile
        var instance: BajajBridgeApp? = null
            private set

        fun get(context: Context): BajajBridgeApp {
            instance?.let { return it }
            val app = context.applicationContext as? BajajBridgeApp
            if (app != null) {
                instance = app
                return app
            }
            throw IllegalStateException("Application context is not BajajBridgeApp")
        }
    }
}
