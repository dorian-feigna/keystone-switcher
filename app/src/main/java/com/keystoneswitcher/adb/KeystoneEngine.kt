package com.keystoneswitcher.adb

import android.content.Context
import android.os.SystemClock
import com.keystoneswitcher.data.EngineConfig
import com.keystoneswitcher.data.KeystonePosition
import com.keystoneswitcher.data.PositionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Principe :
 *  1. « Nouvelle position » : photographie de tous les réglages système (settings global/system/secure + getprop),
 *     puis ouverture du réglage trapèze XGIMI (ou ouverture manuelle à la télécommande la première fois).
 *  2. Au retour dans l'app : seconde photographie. Les réglages qui ont changé = ceux de la correction trapézoïdale.
 *     Leurs valeurs actuelles sont enregistrées sous le nom choisi.
 *  3. « Appliquer » : réécriture de ces valeurs via ADB (settings put / setprop).
 */
class KeystoneEngine(
    context: Context,
    private val adb: AdbClient,
    private val repository: PositionRepository
) {

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val SESSION_MAX_MS = 10 * 60_000L
        private val SETTINGS_NAMESPACES = listOf("global", "system", "secure")
        private val PROP_REGEX = Regex("""^\[(.+?)]: \[(.*)]$""")
        private val RESUMED_REGEX =
            Regex("""ActivityRecord\{[0-9a-f]+ u\d+ ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""")

        /** Réglages qui bougent tout seuls (horloge, compteurs, réseau…) : jamais retenus. */
        private val NOISE_REGEX = Regex(
            "(?i)(time|stamp|uptime|boot|count|last_|battery|wifi|wlan|bluetooth|bt_|volume|ntp|usage|" +
                "screen_off|heartbeat|random|seed|session|token|net\\.|dhcp|ipv|gsm|telephony)"
        )

        /** Noms évocateurs de la correction trapézoïdale : prioritaires s'il y en a. */
        private val KEYSTONE_HINT_REGEX = Regex(
            "(?i)(keystone|trapez|kst|corner|warp|distort|correct|projector|xgimi|" +
                "left_top|right_top|left_bottom|right_bottom|lt_|rt_|lb_|rb_)"
        )

        /** Mots évocateurs dans le nom d'une activité, pour reconnaître le menu trapèze. */
        private val MENU_HINT_REGEX = Regex("(?i)(keystone|trapez|correct|calib|warp|distort|projection)")
    }

    private val ownPackage = context.applicationContext.packageName

    private class CaptureSession(val baseline: Map<String, String>, val openedDirectly: Boolean) {
        val seenComponents = mutableListOf<String>() // protégé par synchronized(seenComponents)
        var pollJob: Job? = null
    }

    @Volatile
    private var session: CaptureSession? = null

    val hasPendingSession: Boolean
        get() = session != null

    // ------------------------------------------------------------------
    // 1. Démarrer une nouvelle position
    // ------------------------------------------------------------------

    /** Retourne le message à afficher. Lance une exception si ADB n'est pas disponible. */
    suspend fun startNewPosition(scope: CoroutineScope): String {
        val state = adb.ensureConnected()
        if (state !is AdbState.Connected) throw IllegalStateException("ADB non connecté : ${adb.describe(state)}")

        cancelSession()
        val baseline = snapshot()
        val config = repository.currentConfig()

        val opened = config.menuComponent.isNotBlank() && tryOpen(config.menuComponent)
        val newSession = CaptureSession(baseline, openedDirectly = opened)
        session = newSession

        // Apprentissage du menu : on note les écrans visités pendant que l'app est en arrière-plan
        newSession.pollJob = scope.launch(Dispatchers.IO) {
            val deadline = SystemClock.uptimeMillis() + SESSION_MAX_MS
            while (isActive && session === newSession && SystemClock.uptimeMillis() < deadline) {
                val component = runCatching { currentForegroundComponent() }.getOrNull()
                if (component != null) {
                    synchronized(newSession.seenComponents) {
                        if (newSession.seenComponents.lastOrNull() != component) newSession.seenComponents.add(component)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        return if (opened) {
            "Réglage trapèze ouvert. Ajuste la position, puis appuie sur Retour pour revenir ici et l'enregistrer."
        } else {
            "Ouvre le réglage trapèze MANUEL avec la télécommande, ajuste la position, puis reviens dans " +
                "Keystone Switcher pour l'enregistrer. (L'app retiendra ce menu pour l'ouvrir elle-même la prochaine fois.)"
        }
    }

    fun cancelSession() {
        session?.pollJob?.cancel()
        session = null
    }

    // ------------------------------------------------------------------
    // 2. Enregistrer la position au retour dans l'app
    // ------------------------------------------------------------------

    suspend fun finishSession(name: String): String {
        val current = session ?: throw IllegalStateException("Aucun réglage en cours : appuie d'abord sur « Nouvelle position ».")
        current.pollJob?.cancel()

        val after = snapshot()
        val changed = diff(current.baseline, after)
        val config = repository.currentConfig()

        // Mémorise le menu pour l'ouvrir directement la prochaine fois
        val learnedMenu = if (current.openedDirectly) null else pickMenuComponent(synchronized(current.seenComponents) {
            current.seenComponents.toList()
        })

        val keys = config.keystoneKeys + changed
        session = null

        if (learnedMenu != null) {
            repository.updateConfig { it.copy(menuComponent = learnedMenu) }
        }

        if (keys.isEmpty()) {
            return "Aucun réglage n'a changé. Modifie bien la correction trapézoïdale avant de revenir, puis réessaie. " +
                "Si le problème persiste, XGIMI stocke ces valeurs dans une zone inaccessible sans root."
        }

        val values = keys.mapNotNull { key -> after[key]?.let { key to it } }.toMap()
        val replaced = repository.savePosition(KeystonePosition(name, values, System.currentTimeMillis()))
        repository.updateConfig { it.copy(keystoneKeys = keys) }

        val shown = values.keys.sorted().take(6).joinToString(", ") { it.substringAfter(':') }
        val more = if (values.size > 6) " …" else ""
        val action = if (replaced) "remplacée" else "enregistrée"
        return "Position « $name » $action (${values.size} réglage(s) : $shown$more)."
    }

    // ------------------------------------------------------------------
    // 3. Appliquer une position
    // ------------------------------------------------------------------

    suspend fun applyPosition(position: KeystonePosition): String {
        val state = adb.ensureConnected()
        if (state !is AdbState.Connected) throw IllegalStateException("ADB non connecté : ${adb.describe(state)}")
        if (position.values.isEmpty()) {
            return "« ${position.name} » ne contient aucun réglage : enregistre-la de nouveau."
        }
        val errors = position.values.mapNotNull { (key, value) -> write(key, value) }
        val ok = position.values.size - errors.size
        return if (errors.isEmpty()) {
            "Position « ${position.name} » appliquée ($ok réglage(s) écrit(s))."
        } else {
            "« ${position.name} » : $ok réglage(s) écrit(s), ${errors.size} refusé(s) → ${errors.take(3).joinToString(" ; ")}"
        }
    }

    // ------------------------------------------------------------------
    // Outils ADB
    // ------------------------------------------------------------------

    /** Photographie de tous les réglages lisibles : "global:clé", "system:clé", "secure:clé", "prop:clé". */
    private suspend fun snapshot(): Map<String, String> {
        val map = HashMap<String, String>()
        for (namespace in SETTINGS_NAMESPACES) {
            adb.shell("settings list $namespace").output.lineSequence().forEach { line ->
                val separator = line.indexOf('=')
                if (separator > 0) map["$namespace:${line.substring(0, separator)}"] = line.substring(separator + 1)
            }
        }
        adb.shell("getprop").output.lineSequence().forEach { line ->
            PROP_REGEX.find(line.trim())?.let { m -> map["prop:${m.groupValues[1]}"] = m.groupValues[2] }
        }
        return map
    }

    private fun diff(before: Map<String, String>, after: Map<String, String>): Set<String> {
        val changed = after.filter { (key, value) -> before[key] != value }.keys
            .filterNot { isNoise(it) }
            .toSet()
        val hinted = changed.filter { KEYSTONE_HINT_REGEX.containsMatchIn(it) }.toSet()
        return if (hinted.isNotEmpty()) hinted else changed
    }

    private fun isNoise(key: String): Boolean {
        if (key.startsWith("prop:ro.") || key.startsWith("prop:init.svc.")) return true
        return NOISE_REGEX.containsMatchIn(key.substringAfter(':')) && !KEYSTONE_HINT_REGEX.containsMatchIn(key)
    }

    /** Retourne null si l'écriture a réussi, sinon un message d'erreur court. */
    private suspend fun write(key: String, value: String): String? {
        val namespace = key.substringBefore(':')
        val name = key.substringAfter(':')
        val command = when (namespace) {
            "global", "system", "secure" -> "settings put $namespace ${quote(name)} ${quote(value)}"
            "prop" -> "setprop ${quote(name)} ${quote(value)}"
            else -> return "$name : espace inconnu"
        }
        val response = adb.shell(command)
        val out = response.allOutput.trim()
        val failed = response.exitCode != 0 ||
            out.contains("Exception") ||
            out.contains("Failed", ignoreCase = true) ||
            out.contains("Error", ignoreCase = true)
        return if (failed) "$name : ${out.ifEmpty { "code ${response.exitCode}" }.take(80)}" else null
    }

    private fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private suspend fun tryOpen(component: String): Boolean {
        val response = adb.shell("am start -n ${quote(component)}")
        val out = response.allOutput
        return response.exitCode == 0 &&
            !out.contains("Error", ignoreCase = true) &&
            !out.contains("Exception") &&
            !out.contains("Permission Denial")
    }

    private suspend fun currentForegroundComponent(): String? {
        val out = adb.shell("dumpsys activity activities | grep -E 'ResumedActivity'").output
        val match = out.lineSequence().mapNotNull { RESUMED_REGEX.find(it) }.firstOrNull() ?: return null
        val pkg = match.groupValues[1]
        val cls = match.groupValues[2]
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else "$pkg/$cls"
    }

    private suspend fun homePackage(): String? = runCatching {
        adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .output.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') }?.substringBefore('/')
    }.getOrNull()

    /** Choisit, parmi les écrans visités, celui qui est le plus probablement le réglage trapèze. */
    private suspend fun pickMenuComponent(seen: List<String>): String? {
        val home = homePackage()
        val candidates = seen.filter {
            val pkg = it.substringBefore('/')
            pkg != ownPackage && pkg != home && pkg != "com.android.systemui"
        }
        return candidates.lastOrNull { MENU_HINT_REGEX.containsMatchIn(it) } ?: candidates.lastOrNull()
    }

    suspend fun currentConfig(): EngineConfig = repository.currentConfig()
}
