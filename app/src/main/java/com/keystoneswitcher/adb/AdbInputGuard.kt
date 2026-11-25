package com.keystoneswitcher.adb

import android.os.SystemClock
import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb

/**
 * Coupe-circuit ADB, indépendant du service d'accessibilité.
 *
 * Ouvre une connexion ADB DÉDIÉE et lance `getevent -lq` dans un pseudo-terminal (pty, sortie non bufferisée).
 * `getevent` lit les périphériques d'entrée réels (/dev/input) : seule la télécommande y apparaît.
 * Les touches injectées par `input keyevent` ne passent pas par /dev/input => aucun faux positif.
 *
 * Connexion dédiée : le thread de lecture est le seul lecteur du socket, donc [close] le débloque à coup sûr.
 */
class AdbInputGuard(private val keyPair: AdbKeyPair) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val GETEVENT_COMMAND = "shell,v2,pty:getevent -lq"
    }

    @Volatile
    private var dadb: Dadb? = null

    @Volatile
    var closed = false
        private set

    /** Bloquant : connexion + démarrage de getevent. */
    fun open(): AdbShellStream {
        val connection = Dadb.create(DadbKeystoneExecutor.HOST, DadbKeystoneExecutor.PORT, keyPair, CONNECT_TIMEOUT_MS, 0)
        dadb = connection
        if (closed) {
            close()
            throw IllegalStateException("Coupe-circuit fermé avant démarrage")
        }
        return AdbShellStream(connection.open(GETEVENT_COMMAND))
    }

    /**
     * Bloquant : lit les événements jusqu'à [close] ou la fin de getevent.
     * [onPhysicalKey] est appelé pour chaque APPUI (DOWN) d'une touche KEY_* après [armedAtUptimeMs].
     */
    fun readLoop(stream: AdbShellStream, armedAtUptimeMs: Long, onPhysicalKey: (String) -> Unit) {
        val buffer = StringBuilder()
        while (!closed) {
            val packet = try {
                stream.read()
            } catch (e: Exception) {
                return // close() ferme le socket : fin normale
            }
            if (packet is AdbShellPacket.Exit) return
            buffer.append(String(packet.payload))
            var newline = buffer.indexOf("\n")
            while (newline >= 0) {
                val line = buffer.substring(0, newline).trim()
                buffer.delete(0, newline + 1)
                parseKeyDown(line)?.let { key ->
                    if (SystemClock.uptimeMillis() >= armedAtUptimeMs) onPhysicalKey(key)
                }
                newline = buffer.indexOf("\n")
            }
        }
    }

    /** Ligne type : "/dev/input/event3: EV_KEY       KEY_BACK             DOWN" */
    private fun parseKeyDown(line: String): String? {
        val parts = line.split(Regex("\\s+"))
        val index = parts.indexOf("EV_KEY")
        if (index < 0 || parts.size <= index + 2) return null
        val key = parts[index + 1]
        val action = parts[index + 2]
        return if (key.startsWith("KEY_") && action == "DOWN") key else null
    }

    fun close() {
        closed = true
        runCatching { dadb?.close() }
    }
}
