package com.keystoneswitcher.adb

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.keystoneswitcher.data.Corner
import com.keystoneswitcher.data.DeviceConfig
import com.keystoneswitcher.data.KeySequence
import com.keystoneswitcher.data.KeystoneProfile
import com.keystoneswitcher.data.LaunchMode
import com.keystoneswitcher.data.ProfileRepository
import com.keystoneswitcher.guard.ExecutionController
import com.keystoneswitcher.guard.KeystoneGuardService
import dadb.AdbKeyPair
import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.abs

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

sealed class DetectionState {
    object Idle : DetectionState()
    object CheckingAdb : DetectionState()
    data class Countdown(val secondsLeft: Int) : DetectionState()
    object Capturing : DetectionState()
    data class Done(val summary: String) : DetectionState()
    data class Failed(val message: String) : DetectionState()
}

data class DetectionResult(
    val resumedComponent: String?,   // package/activité issue de mResumedActivity
    val focusedWindow: String?,      // jeton brut de mCurrentFocus
    val focusedPackage: String?,
    val uiPackages: List<String>,
    val uiTexts: List<String>,
    val resetCandidates: List<String>,
    val rawActivity: String,
    val rawWindow: String,
    val uiDumpMessage: String
) {
    fun report(): String = buildString {
        appendLine("Activité au premier plan : ${resumedComponent ?: "non trouvée"}")
        appendLine("Fenêtre focalisée       : ${focusedWindow ?: "non trouvée"}")
        appendLine("Package focalisé        : ${focusedPackage ?: "inconnu"}")
        appendLine("Packages (uiautomator)  : ${uiPackages.joinToString().ifEmpty { "—" }}")
        appendLine("Libellés détectés       : ${uiTexts.joinToString(" | ").ifEmpty { "—" }}")
        appendLine("Candidats « remise à zéro » : ${resetCandidates.joinToString(" | ").ifEmpty { "aucun" }}")
        appendLine("uiautomator : $uiDumpMessage")
        appendLine()
        appendLine("--- dumpsys activity (extrait) ---")
        appendLine(rawActivity.trim().ifEmpty { "(vide)" })
        appendLine("--- dumpsys window (extrait) ---")
        append(rawWindow.trim().ifEmpty { "(vide)" })
    }
}

/**
 * Moteur ADB local : dadb -> 127.0.0.1:5555 -> `input keyevent`.
 * Toutes les commandes shell passent par un Mutex : une seule opération ADB à la fois.
 */
class DadbKeystoneExecutor(context: Context, private val repository: ProfileRepository) {

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        const val DETECTION_COUNTDOWN_S = 20
        private const val TCP_PROBE_TIMEOUT_MS = 1_500
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val AUTH_TIMEOUT_MS = 60_000          // temps laissé pour accepter la clé RSA sur la TV
        private const val AUTH_PENDING_HINT_MS = 2_500L     // au-delà : on considère la fenêtre RSA affichée
        private const val GUARD_START_TIMEOUT_MS = 4_000L
        private const val GUARD_GRACE_MS = 400L            // ignore le relâchement de la touche OK qui a lancé le profil
        private const val FOREGROUND_RECHECK_EVERY = 8      // revérifie le premier plan toutes les N touches
        private const val UI_DUMP_PATH = "/data/local/tmp/keystone_ui_dump.xml"
        private val RESUMED_REGEX =
            Regex("""ActivityRecord\{[0-9a-f]+ u\d+ ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""")
        private val FOCUS_REGEX = Regex("""mCurrentFocus=Window\{[0-9a-f]+ u\d+ ([^}]+)\}""")
        private val UI_PACKAGE_REGEX = Regex("""package="([^"]+)"""")
        private val UI_TEXT_REGEX = Regex("""(?:text|content-desc)="([^"]+)"""")
        private val RESET_REGEX =
            Regex("""(?i)(r[ée]initialis|reset|restor|restaur|d[ée]faut|default|z[ée]ro)""")
    }

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var dadb: Dadb? = null // protégé par mutex

    private val keyPair: AdbKeyPair by lazy { loadOrCreateKeyPair() }

    private val _adbState = MutableStateFlow<AdbState>(AdbState.Unknown)
    val adbState: StateFlow<AdbState> = _adbState.asStateFlow()

    private val _detectionState = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val detectionState: StateFlow<DetectionState> = _detectionState.asStateFlow()

    private var detectionJob: Job? = null

    // ------------------------------------------------------------------
    // Clé RSA persistante (filesDir/adb_keys) : l'autorisation n'est demandée qu'une fois
    // ------------------------------------------------------------------

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
            // Fichiers corrompus : régénération (une nouvelle autorisation sera demandée)
            privateKey.delete()
            publicKey.delete()
            AdbKeyPair.generate(privateKey, publicKey)
            AdbKeyPair.read(privateKey, publicKey)
        }
    }

    // ------------------------------------------------------------------
    // Connexion et états
    // ------------------------------------------------------------------

    /** Ferme la connexion existante et reteste de zéro (bouton « Tester / Autoriser ADB »). */
    suspend fun testConnection(): AdbState = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeDadbLocked()
            try {
                connectLocked()
            } catch (e: IOException) {
                // l'état explicite a déjà été publié par connectLocked()
            }
            _adbState.value
        }
    }

    private suspend fun ensureConnected(): AdbState =
        if (_adbState.value is AdbState.Connected) _adbState.value else testConnection()

    private fun closeDadbLocked() {
        runCatching { dadb?.close() }
        dadb = null
    }

    private suspend fun connectLocked(): Dadb {
        dadb?.let { return it }
        _adbState.value = AdbState.Checking

        // 1. Sonde TCP : distingue « débogage réseau désactivé » d'un problème d'autorisation
        try {
            Socket().use { it.connect(InetSocketAddress(HOST, PORT), TCP_PROBE_TIMEOUT_MS) }
        } catch (e: IOException) {
            val msg = "Port $HOST:$PORT fermé (${e.javaClass.simpleName}). Active le débogage ADB réseau " +
                "dans les Options pour les développeurs du projecteur."
            _adbState.value = AdbState.NetworkDebugDisabled(msg)
            throw AdbUnavailableException(msg)
        }

        // 2. Handshake ADB + authentification RSA
        val candidate = Dadb.create(HOST, PORT, keyPair, CONNECT_TIMEOUT_MS, AUTH_TIMEOUT_MS)
        try {
            val response = coroutineScope {
                val pendingHint = launch {
                    delay(AUTH_PENDING_HINT_MS)
                    _adbState.value = AdbState.AuthPending
                }
                try {
                    candidate.shell("getprop ro.product.model")
                } finally {
                    pendingHint.cancel()
                }
            }
            dadb = candidate
            _adbState.value = AdbState.Connected(response.output.trim().ifEmpty { "Android TV" })
            return candidate
        } catch (e: SocketTimeoutException) {
            runCatching { candidate.close() }
            val msg = "Autorisation RSA non accordée sous ${AUTH_TIMEOUT_MS / 1000} s. Relance le test et " +
                "accepte la fenêtre « Autoriser le débogage » en cochant « Toujours autoriser »."
            _adbState.value = AdbState.Error(msg)
            throw AdbUnavailableException(msg)
        } catch (e: IOException) {
            runCatching { candidate.close() }
            val detail = e.message ?: e.javaClass.simpleName
            if (detail.contains("Connection failed", ignoreCase = true)) {
                val msg = "Connexion ADB refusée ($detail). L'autorisation a probablement été refusée."
                _adbState.value = AdbState.AuthRejected(msg)
                throw AdbUnavailableException(msg)
            }
            val msg = "Erreur ADB : $detail"
            _adbState.value = AdbState.Error(msg)
            throw AdbUnavailableException(msg)
        }
    }

    /**
     * Exécute une commande shell. [retry] = nouvelle tentative après reconnexion
     * (désactivé pour les touches : une touche ne doit jamais être envoyée deux fois).
     */
    private suspend fun shell(command: String, retry: Boolean = true): AdbShellResponse =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val first = connectLocked()
                try {
                    first.shell(command)
                } catch (e: IOException) {
                    closeDadbLocked()
                    _adbState.value = AdbState.Error("Connexion ADB perdue : ${e.message ?: e.javaClass.simpleName}")
                    if (!retry) throw e
                    val second = connectLocked()
                    second.shell(command)
                }
            }
        }

    // ------------------------------------------------------------------
    // Envoi unitaire de touche
    // ------------------------------------------------------------------

    suspend fun sendKey(keyCode: Int) {
        val response = shell("input keyevent $keyCode", retry = false)
        if (response.exitCode != 0) {
            throw IOException("input keyevent $keyCode a échoué (code ${response.exitCode}) : ${response.allOutput.trim()}")
        }
    }

    // ------------------------------------------------------------------
    // Activation du coupe-circuit via ADB (shell possède WRITE_SECURE_SETTINGS)
    // ------------------------------------------------------------------

    suspend fun enableGuardViaAdb(): String {
        val me = KeystoneGuardService.componentName(appContext)
        val flat = me.flattenToString()
        val current = shell("settings get secure enabled_accessibility_services").output.trim()
        val services = current
            .takeUnless { it.isEmpty() || it == "null" }
            ?.split(':')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val alreadyListed = services.any { android.content.ComponentName.unflattenFromString(it) == me }
        val newValue = if (alreadyListed) services.joinToString(":") else (services + flat).joinToString(":")
        shell("settings put secure enabled_accessibility_services '$newValue'")
        shell("settings put secure accessibility_enabled 1")
        return if (alreadyListed) "Service déjà déclaré : réactivation demandée." else "Service coupe-circuit activé via ADB."
    }

    // ------------------------------------------------------------------
    // Auto-détection guidée du menu trapèze XGIMI
    // ------------------------------------------------------------------

    fun startDetection(scope: CoroutineScope) {
        if (detectionJob?.isActive == true) return
        detectionJob = scope.launch(Dispatchers.Main) {
            _detectionState.value = DetectionState.CheckingAdb
            val state = ensureConnected()
            if (state !is AdbState.Connected) {
                _detectionState.value = DetectionState.Failed("ADB non connecté : ${describe(state)}")
                return@launch
            }
            for (s in DETECTION_COUNTDOWN_S downTo 1) {
                _detectionState.value = DetectionState.Countdown(s)
                delay(1_000)
            }
            _detectionState.value = DetectionState.Capturing
            try {
                val result = captureForeground()
                val summary = applyDetection(result)
                _detectionState.value = DetectionState.Done(summary)
                Toast.makeText(appContext, "Keystone Switcher : $summary", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "Détection impossible : ${e.message ?: e.javaClass.simpleName}"
                _detectionState.value = DetectionState.Failed(msg)
                Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun captureForeground(): DetectionResult {
        val rawActivity = shell("dumpsys activity activities | grep -E 'ResumedActivity'").output
        val rawWindow = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'").output

        val resumed = rawActivity.lineSequence()
            .mapNotNull { RESUMED_REGEX.find(it) }
            .firstOrNull()
            ?.let { m -> normalizeComponent(m.groupValues[1], m.groupValues[2]) }

        val focusToken = FOCUS_REGEX.find(rawWindow)?.groupValues?.get(1)?.trim()

        // uiautomator : structure de l'écran (best effort, peut échouer pendant une animation)
        val dumpResponse = shell("uiautomator dump $UI_DUMP_PATH")
        val xml = shell("cat $UI_DUMP_PATH").output
        shell("rm -f $UI_DUMP_PATH")
        val uiOk = xml.contains("<hierarchy")
        val uiPackages = if (uiOk) UI_PACKAGE_REGEX.findAll(xml).map { it.groupValues[1] }.distinct().toList() else emptyList()
        val uiTexts = if (uiOk) {
            UI_TEXT_REGEX.findAll(xml).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().take(40).toList()
        } else {
            emptyList()
        }

        val focusedPackage = when {
            focusToken != null && focusToken.contains('/') -> focusToken.substringBefore('/')
            uiPackages.isNotEmpty() -> uiPackages.first()
            else -> null
        }

        return DetectionResult(
            resumedComponent = resumed,
            focusedWindow = focusToken,
            focusedPackage = focusedPackage,
            uiPackages = uiPackages,
            uiTexts = uiTexts,
            resetCandidates = uiTexts.filter { RESET_REGEX.containsMatchIn(it) },
            rawActivity = rawActivity,
            rawWindow = rawWindow,
            uiDumpMessage = if (uiOk) "OK" else dumpResponse.allOutput.trim().ifEmpty { "échec sans message" }
        )
    }

    private fun normalizeComponent(pkg: String, cls: String): String =
        if (cls.startsWith(".")) "$pkg/$pkg$cls" else "$pkg/$cls"

    /** Enregistre le résultat dans DeviceConfig et retourne un résumé lisible. */
    private suspend fun applyDetection(result: DetectionResult): String {
        val own = appContext.packageName
        val resumedPkg = result.resumedComponent?.substringBefore('/')
        val focusPkg = result.focusedPackage
        val focusComponent = result.focusedWindow
            ?.takeIf { it.contains('/') }
            ?.let { token -> normalizeComponent(token.substringBefore('/'), token.substringAfter('/')) }
        val report = result.report()

        if (resumedPkg == own || focusPkg == own) {
            repository.updateDeviceConfig { it.copy(lastDetectionReport = report) }
            return "Keystone Switcher était encore au premier plan. Ouvre le menu trapèze XGIMI pendant le compte à rebours."
        }

        val target = when {
            focusComponent != null -> focusComponent
            result.resumedComponent != null && (focusPkg == null || focusPkg == resumedPkg) -> result.resumedComponent
            else -> null
        }
        val watch = focusPkg ?: resumedPkg ?: ""

        return if (target != null) {
            repository.updateDeviceConfig {
                it.copy(
                    targetComponent = target,
                    watchPackage = watch,
                    launchMode = LaunchMode.ACTIVITY,
                    lastDetectionReport = report
                )
            }
            "Menu détecté : $target"
        } else {
            // Fenêtre superposée (overlay) : non lançable par am start
            repository.updateDeviceConfig {
                it.copy(
                    watchPackage = watch.ifEmpty { it.watchPackage },
                    launchMode = LaunchMode.KEY_SEQUENCE,
                    lastDetectionReport = report
                )
            }
            "Fenêtre superposée détectée (package : ${watch.ifEmpty { "inconnu" }}). Renseigne la séquence d'ouverture."
        }
    }

    // ------------------------------------------------------------------
    // Exécution séquentielle d'un profil
    // ------------------------------------------------------------------

    private sealed class Step(val label: String) {
        object OpenMenu : Step("Ouverture du menu trapèze")
        object VerifyForeground : Step("Vérification du premier plan")
        class Pause(val ms: Long, label: String) : Step(label)
        class Press(val keyCode: Int, label: String) : Step(label)
    }

    /** Lance un profil. Retourne un message d'erreur si la séquence n'a pas pu démarrer, sinon null. */
    fun runProfile(scope: CoroutineScope, profile: KeystoneProfile): String? =
        startRun(scope, "Profil « ${profile.profileName} »", profile, null, includeRestore = true)

    /** Test de calibration : ouverture seule, ou ouverture + remise à zéro, avec une config non enregistrée. */
    fun runCalibrationTest(scope: CoroutineScope, config: DeviceConfig, includeRestore: Boolean): String? =
        startRun(
            scope,
            if (includeRestore) "Test ouverture + remise à zéro" else "Test ouverture du menu",
            null,
            config,
            includeRestore
        )

    private fun startRun(
        scope: CoroutineScope,
        label: String,
        profile: KeystoneProfile?,
        configOverride: DeviceConfig?,
        includeRestore: Boolean
    ): String? {
        val started = ExecutionController.launch(scope, label) { progress ->
            val config = configOverride ?: repository.currentDeviceConfig()
            val steps = buildPlan(profile, config, includeRestore)
            val stepDelay = profile?.stepDelayMs ?: KeystoneProfile.DEFAULT_STEP_DELAY_MS
            executePlan(steps, stepDelay, config, progress)
        }
        return if (started) null else "Une séquence est déjà en cours."
    }

    private fun buildPlan(profile: KeystoneProfile?, config: DeviceConfig, includeRestore: Boolean): List<Step> {
        val steps = mutableListOf<Step>()
        when (config.launchMode) {
            LaunchMode.ACTIVITY -> {
                require(config.targetComponent.contains('/')) {
                    "Aucune activité trapèze enregistrée : lance « Calibrer / Détecter le menu XGIMI »."
                }
                steps += Step.OpenMenu
            }
            LaunchMode.KEY_SEQUENCE -> {
                require(config.openSequence.isNotEmpty()) {
                    "Mode séquence choisi mais la séquence d'ouverture est vide (écran Calibration)."
                }
                config.openSequence.forEach { steps += Step.Press(it, "Ouverture : ${KeySequence.label(it)}") }
            }
        }
        steps += Step.Pause(config.launchSettleMs, "Attente de l'affichage du menu")
        if (config.foregroundGuard && config.watchPackage.isNotBlank()) steps += Step.VerifyForeground

        if (includeRestore && config.restoreSequence.isNotEmpty()) {
            config.restoreSequence.forEach { steps += Step.Press(it, "Remise à zéro : ${KeySequence.label(it)}") }
            steps += Step.Pause(config.restoreSettleMs, "Attente après remise à zéro")
        }

        if (profile != null) {
            for (corner in Corner.values()) {
                val offset = profile.offset(corner)
                config.selectFor(corner).forEach {
                    steps += Step.Press(it, "${corner.label} : sélection (${KeySequence.label(it)})")
                }
                val xKey = if (offset.x >= 0) 22 else 21
                repeat(abs(offset.x)) { i ->
                    steps += Step.Press(xKey, "${corner.label} : X ${i + 1}/${abs(offset.x)}")
                }
                val yKey = if (offset.y >= 0) 19 else 20
                repeat(abs(offset.y)) { i ->
                    steps += Step.Press(yKey, "${corner.label} : Y ${i + 1}/${abs(offset.y)}")
                }
                config.commitFor(corner).forEach {
                    steps += Step.Press(it, "${corner.label} : validation (${KeySequence.label(it)})")
                }
            }
        }

        config.finishSequence.forEach { steps += Step.Press(it, "Fin : ${KeySequence.label(it)}") }
        return steps
    }

    private suspend fun executePlan(
        steps: List<Step>,
        stepDelayMs: Long,
        config: DeviceConfig,
        progress: (Int, Int, String) -> Unit
    ) {
        // Coupe-circuit principal : toute touche PHYSIQUE de la télécommande annule la séquence.
        val guard = AdbInputGuard(keyPair)
        val ready = CompletableDeferred<Unit>()
        Thread({
            try {
                val stream = guard.open()
                ready.complete(Unit)
                guard.readLoop(stream, SystemClock.uptimeMillis() + GUARD_GRACE_MS) { key ->
                    ExecutionController.abort("Touche $key pressée sur la télécommande")
                }
                if (!guard.closed) ExecutionController.abort("Coupe-circuit ADB interrompu (getevent arrêté)")
            } catch (e: Exception) {
                ready.completeExceptionally(e)
            }
        }, "keystone-adb-guard").apply {
            isDaemon = true
            start()
        }

        try {
            try {
                withTimeout(GUARD_START_TIMEOUT_MS) { ready.await() }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("Coupe-circuit ADB indisponible : getevent n'a pas démarré.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Coupe-circuit ADB indisponible : ${e.message ?: e.javaClass.simpleName}")
            }
            delay(GUARD_GRACE_MS)

            val total = steps.size
            var pressCount = 0
            var foregroundVerified = false
            steps.forEachIndexed { index, step ->
                currentCoroutineContext().ensureActive()
                progress(index + 1, total, step.label)
                when (step) {
                    is Step.OpenMenu -> openMenu(config.targetComponent)
                    is Step.VerifyForeground -> {
                        verifyForeground(config.watchPackage)
                        ExecutionController.armWatch(config.watchPackage)
                        foregroundVerified = true
                    }
                    is Step.Pause -> delay(step.ms)
                    is Step.Press -> {
                        sendKey(step.keyCode)
                        pressCount++
                        delay(stepDelayMs)
                        if (foregroundVerified && pressCount % FOREGROUND_RECHECK_EVERY == 0) {
                            verifyForeground(config.watchPackage)
                        }
                    }
                }
            }
        } finally {
            guard.close()
        }
    }

    private suspend fun openMenu(component: String) {
        val response = shell("am start -n '$component'")
        val out = response.allOutput
        if (response.exitCode != 0 ||
            out.contains("Error", ignoreCase = true) ||
            out.contains("Exception") ||
            out.contains("Permission Denial")
        ) {
            throw IllegalStateException(
                "Ouverture refusée pour $component : ${out.trim()}. " +
                    "Si l'activité n'est pas exportée, passe en mode « Séquence de touches »."
            )
        }
    }

    private suspend fun verifyForeground(watchPackage: String) {
        val out = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'").output
        if (!out.contains(watchPackage)) {
            val current = out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "inconnu"
            throw IllegalStateException("Menu XGIMI absent du premier plan (attendu : $watchPackage ; actuel : $current).")
        }
    }

    fun describe(state: AdbState): String = when (state) {
        AdbState.Unknown -> "Non testé"
        AdbState.Checking -> "Connexion à $HOST:$PORT…"
        is AdbState.Connected -> "Connecté à $HOST:$PORT (${state.deviceModel})"
        AdbState.AuthPending -> "Autorisation RSA en attente : accepte la fenêtre sur l'écran TV (coche « Toujours autoriser »)"
        is AdbState.AuthRejected -> state.detail
        is AdbState.NetworkDebugDisabled -> state.detail
        is AdbState.Error -> state.message
    }
}
