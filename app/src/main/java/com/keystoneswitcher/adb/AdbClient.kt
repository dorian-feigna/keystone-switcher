package com.keystoneswitcher.adb

import android.content.Context
import dadb.AdbKeyPair
import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class AdbUnavailableException(message: String) : IOException(message)

sealed class AdbState {
    object Unknown : AdbState()
    object Checking : AdbState()
    data class Connected(val deviceModel: String) : AdbState()
    object AuthPending : AdbState()
    data class AuthRejected(val detail: String) : AdbState()
    data class NetworkDebugDisabled(val detail: String) : AdbState()
    data class Error(val message: String) : AdbState()
}

/**
 * Client ADB local : dadb -> 127.0.0.1:5555.
 * Clé RSA persistante dans filesDir/adb_keys : l'autorisation n'est demandée qu'une seule fois.
 * Toutes les commandes passent par un Mutex : une seule opération ADB à la fois.
 */
class AdbClient(context: Context) {

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        private const val TCP_PROBE_TIMEOUT_MS = 1_500
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val AUTH_TIMEOUT_MS = 60_000      // temps laissé pour accepter la clé RSA sur la TV
        private const val AUTH_PENDING_HINT_MS = 2_500L // au-delà : fenêtre RSA probablement affichée
    }

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var dadb: Dadb? = null // protégé par mutex

    private val keyPair: AdbKeyPair by lazy { loadOrCreateKeyPair() }

    private val _state = MutableStateFlow<AdbState>(AdbState.Unknown)
    val state: StateFlow<AdbState> = _state.asStateFlow()

    private fun loadOrCreateKeyPair(): AdbKeyPair {
        val dir = File(appContext.filesDir, "adb_keys").apply { mkdirs() }
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists() || privateKey.length() == 0L) {
            privateKey.delete()
            publicKey.delete()
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return try {
            AdbKeyPair.read(privateKey, publicKey)
        } catch (e: Exception) {
            privateKey.delete()
            publicKey.delete()
            AdbKeyPair.generate(privateKey, publicKey)
            AdbKeyPair.read(privateKey, publicKey)
        }
    }

    /** Ferme la connexion existante et reteste de zéro (bouton « Tester / Autoriser ADB »). */
    suspend fun testConnection(): AdbState = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            try {
                connectLocked()
            } catch (e: IOException) {
                // état explicite déjà publié par connectLocked()
            }
            _state.value
        }
    }

    suspend fun ensureConnected(): AdbState =
        if (_state.value is AdbState.Connected) _state.value else testConnection()

    /**
     * Exécute une commande shell. [retry] : nouvelle tentative après reconnexion si la connexion a sauté.
     */
    suspend fun shell(command: String, retry: Boolean = true): AdbShellResponse = withContext(Dispatchers.IO) {
        mutex.withLock {
            val first = connectLocked()
            try {
                first.shell(command)
            } catch (e: IOException) {
                closeLocked()
                _state.value = AdbState.Error("Connexion ADB perdue : ${e.message ?: e.javaClass.simpleName}")
                if (!retry) throw e
                val second = connectLocked()
                second.shell(command)
            }
        }
    }

    private fun closeLocked() {
        runCatching { dadb?.close() }
        dadb = null
    }

    private suspend fun connectLocked(): Dadb {
        dadb?.let { return it }
        _state.value = AdbState.Checking

        // 1. Sonde TCP : distingue « débogage réseau désactivé » d'un problème d'autorisation
        try {
            Socket().use { it.connect(InetSocketAddress(HOST, PORT), TCP_PROBE_TIMEOUT_MS) }
        } catch (e: IOException) {
            val msg = "Port $HOST:$PORT fermé. Active le débogage réseau (ADB) dans les Options pour les développeurs."
            _state.value = AdbState.NetworkDebugDisabled(msg)
            throw AdbUnavailableException(msg)
        }

        // 2. Handshake ADB + authentification RSA
        val candidate = Dadb.create(HOST, PORT, keyPair, CONNECT_TIMEOUT_MS, AUTH_TIMEOUT_MS)
        try {
            val response = coroutineScope {
                val pendingHint = launch {
                    delay(AUTH_PENDING_HINT_MS)
                    _state.value = AdbState.AuthPending
                }
                try {
                    candidate.shell("getprop ro.product.model")
                } finally {
                    pendingHint.cancel()
                }
            }
            dadb = candidate
            _state.value = AdbState.Connected(response.output.trim().ifEmpty { "Android TV" })
            return candidate
        } catch (e: SocketTimeoutException) {
            runCatching { candidate.close() }
            val msg = "Autorisation non accordée sous ${AUTH_TIMEOUT_MS / 1000} s. Relance le test et accepte la " +
                "fenêtre « Autoriser le débogage » en cochant « Toujours autoriser »."
            _state.value = AdbState.Error(msg)
            throw AdbUnavailableException(msg)
        } catch (e: IOException) {
            runCatching { candidate.close() }
            val detail = e.message ?: e.javaClass.simpleName
            val msg = if (detail.contains("Connection failed", ignoreCase = true)) {
                "Connexion ADB refusée : l'autorisation a probablement été refusée."
            } else {
                "Erreur ADB : $detail"
            }
            _state.value = if (msg.startsWith("Connexion ADB refusée")) AdbState.AuthRejected(msg) else AdbState.Error(msg)
            throw AdbUnavailableException(msg)
        }
    }

    fun describe(state: AdbState): String = when (state) {
        AdbState.Unknown -> "Non testé"
        AdbState.Checking -> "Connexion à $HOST:$PORT…"
        is AdbState.Connected -> "Connecté (${state.deviceModel})"
        AdbState.AuthPending -> "Autorisation en attente : accepte la fenêtre sur l'écran (coche « Toujours autoriser »)"
        is AdbState.AuthRejected -> state.detail
        is AdbState.NetworkDebugDisabled -> state.detail
        is AdbState.Error -> state.message
    }
}
