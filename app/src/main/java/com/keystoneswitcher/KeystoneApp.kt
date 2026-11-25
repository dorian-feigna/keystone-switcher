package com.keystoneswitcher

import android.app.Application
import android.content.Context
import com.keystoneswitcher.adb.DadbKeystoneExecutor
import com.keystoneswitcher.data.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class KeystoneApp : Application() {

    /** Portée applicative : les séquences continuent quand l'app passe derrière le menu XGIMI. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var repository: ProfileRepository
        private set

    lateinit var executor: DadbKeystoneExecutor
        private set

    override fun onCreate() {
        super.onCreate()
        repository = ProfileRepository(this)
        executor = DadbKeystoneExecutor(this, repository)
    }
}

val Context.app: KeystoneApp
    get() = applicationContext as KeystoneApp
