package com.keystoneswitcher.guard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Point unique de contrôle de la séquence ADB en cours.
 * Le service d'accessibilité appelle [abort] ; l'exécuteur appelle [launch] et [armWatch].
 */
object ExecutionController {

    sealed class RunState {
        object Idle : RunState()
        data class Running(val label: String, val done: Int, val total: Int, val detail: String) : RunState()
        data class Finished(val label: String) : RunState()
        data class Aborted(val label: String, val reason: String) : RunState()
        data class Failed(val label: String, val message: String) : RunState()
    }

    /** Packages tolérés au premier plan pendant une séquence (barre système, claviers). */
    private val IGNORED_PACKAGES = setOf("com.android.systemui")

    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    /** true quand le système a lié le service d'accessibilité. */
    val guardConnected = MutableStateFlow(false)

    @Volatile
    var lastForegroundPackage: String = ""
        private set

    @Volatile
    private var job: Job? = null

    @Volatile
    private var watchedPackage: String? = null

    @Volatile
    private var abortReason: String? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    /** À appeler depuis le thread principal. Retourne false si une séquence tourne déjà. */
    fun launch(
        scope: CoroutineScope,
        label: String,
        block: suspend (progress: (done: Int, total: Int, detail: String) -> Unit) -> Unit
    ): Boolean {
        if (isRunning) return false
        abortReason = null
        watchedPackage = null
        _state.value = RunState.Running(label, 0, 0, "Préparation…")
        val newJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                block { done, total, detail -> _state.value = RunState.Running(label, done, total, detail) }
                _state.value = RunState.Finished(label)
            } catch (e: CancellationException) {
                _state.value = RunState.Aborted(label, abortReason ?: "Annulé")
                throw e
            } catch (e: Exception) {
                _state.value = RunState.Failed(label, e.message ?: e.javaClass.simpleName)
            } finally {
                watchedPackage = null
            }
        }
        job = newJob
        newJob.start()
        return true
    }

    /** Coupe-circuit : annule immédiatement la coroutine d'envoi ADB. */
    fun abort(reason: String) {
        val current = job ?: return
        if (!current.isActive) return
        abortReason = reason
        current.cancel(CancellationException(reason))
    }

    /** Active la surveillance du premier plan une fois le menu XGIMI confirmé à l'écran. */
    fun armWatch(packageName: String) {
        if (packageName.isNotBlank()) watchedPackage = packageName
    }

    fun onForegroundChanged(packageName: String) {
        lastForegroundPackage = packageName
        val watched = watchedPackage ?: return
        if (!isRunning) return
        if (packageName == watched) return
        if (packageName in IGNORED_PACKAGES || packageName.contains("inputmethod", ignoreCase = true)) return
        abort("Écran de réglage XGIMI quitté (premier plan : $packageName)")
    }
}
