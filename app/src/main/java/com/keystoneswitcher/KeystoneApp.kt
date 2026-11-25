package com.keystoneswitcher

import android.app.Application
import android.content.Context
import com.keystoneswitcher.adb.AdbClient
import com.keystoneswitcher.adb.KeystoneEngine
import com.keystoneswitcher.data.PositionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class KeystoneApp : Application() {

    /** Portée applicative : survit au passage de l'app en arrière-plan (menu XGIMI ouvert). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var adb: AdbClient
        private set

    lateinit var repository: PositionRepository
        private set

    lateinit var engine: KeystoneEngine
        private set

    override fun onCreate() {
        super.onCreate()
        adb = AdbClient(this)
        repository = PositionRepository(this)
        engine = KeystoneEngine(this, adb, repository)
    }
}

val Context.app: KeystoneApp
    get() = applicationContext as KeystoneApp
