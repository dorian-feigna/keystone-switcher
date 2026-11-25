package com.keystoneswitcher.adb

import android.content.Context
import android.os.SystemClock
import com.keystoneswitcher.data.KeystonePosition
import com.keystoneswitcher.data.PositionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Les 4 coins sont lisibles dans les propriétés vendor.mstar.pos_{lt,rt,rb,lb}_offset ("x:y"),
 * mais Android interdit de les écrire sans root. Application d'une position = pilotage du menu XGIMI
 * en boucle fermée :
 *  - le menu s'ouvre sur le coin haut-gauche ; OK = coin suivant ; flèches = déplacement ; Retour = valider ;
 *  - après chaque envoi de touches, la position réelle est relue et la correction recalculée.
 */
class KeystoneEngine(
    context: Context,
    private val adb: AdbClient,
    private val repository: PositionRepository
) {

    companion object {
        private const val KEY_UP = 19
        private const val KEY_DOWN = 20
        private const val KEY_LEFT = 21
        private const val KEY_RIGHT = 22
        private const val KEY_OK = 23
        private const val KEY_BACK = 4

        private const val AXIS_X = 0
        private const val AXIS_Y = 1

        private const val OPEN_SETTLE_MS = 1_500L   // attente d'affichage du menu
        private const val KEY_SETTLE_MS = 250L      // attente de mise à jour de la position après des touches
        private const val OK_SETTLE_MS = 400L       // attente après changement de coin
        private const val MAX_BATCH = 12            // touches max envoyées d'un coup
        private const val MAX_ITERATIONS = 60       // garde-fou par axe
        private const val LEARN_TIMEOUT_MS = 120_000L
        private const val LEARN_POLL_MS = 1_500L

        val CORNER_IDS = listOf("lt", "rt", "rb", "lb")
        val CORNER_LABELS = mapOf("lt" to "haut-gauche", "rt" to "haut-droite", "rb" to "bas-droite", "lb" to "bas-gauche")

        private val CORNER_REGEX = Regex("""\[vendor\.mstar\.pos_(lt|rt|lb|rb)_offset]: \[(-?\d+):(-?\d+)]""")
        private val RESUMED_REGEX =
            Regex("""ActivityRecord\{[0-9a-f]+ u\d+ ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""")
        private val MENU_HINT_REGEX = Regex("(?i)(keystone|trapez|correct|calib|warp|distort|projection)")

        fun propKey(cornerId: String) = "prop:vendor.mstar.pos_${cornerId}_offset"
    }

    data class XY(val x: Int, val y: Int) {
        fun get(axis: Int): Int = if (axis == AXIS_X) x else y
        override fun toString() = "$x:$y"
    }

    private class MenuNotRespondingException(message: String) : IllegalStateException(message)

    private val ownPackage = context.applicationContext.packageName

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var learnJob: Job? = null

    // ------------------------------------------------------------------
    // Ouvrir le réglage trapèze (et l'apprendre si nécessaire)
    // ------------------------------------------------------------------

    suspend fun openMenu(scope: CoroutineScope): String {
        ensureAdb()
        val config = repository.currentConfig()
        if (config.menuComponent.isNotBlank() && tryOpen(config.menuComponent)) {
            return "Réglage trapèze ouvert. Une fois l'image ajustée, appuie sur Retour puis « Enregistrer la position actuelle »."
        }
        startLearning(scope)
        return "Je ne sais pas encore ouvrir ce menu : ouvre le réglage trapèze MANUEL à la télécommande, " +
            "puis reviens dans l'app. Il sera retenu pour les fois suivantes."
    }

    private fun startLearning(scope: CoroutineScope) {
        learnJob?.cancel()
        learnJob = scope.launch(Dispatchers.IO) {
            val seen = mutableListOf<String>()
            var sawOtherScreen = false
            val deadline = SystemClock.uptimeMillis() + LEARN_TIMEOUT_MS
            while (isActive && SystemClock.uptimeMillis() < deadline) {
                val component = runCatching { currentForegroundComponent() }.getOrNull()
                if (component != null) {
                    if (component.substringBefore('/') == ownPackage) {
                        if (sawOtherScreen) break // l'utilisateur est revenu dans l'app
                    } else {
                        sawOtherScreen = true
                        if (seen.lastOrNull() != component) seen.add(component)
                    }
                }
                delay(LEARN_POLL_MS)
            }
            val picked = pickMenuComponent(seen)
            _status.value = if (picked != null) {
                repository.updateConfig { it.copy(menuComponent = picked) }
                "Menu trapèze retenu ($picked) : le bouton l'ouvrira directement."
            } else {
                "Menu trapèze non détecté : réessaie « Ouvrir le réglage trapèze »."
            }
        }
    }

    // ------------------------------------------------------------------
    // Enregistrer la position actuelle
    // ------------------------------------------------------------------

    suspend fun saveCurrent(name: String): String {
        ensureAdb()
        val corners = readCorners()
        if (corners.size < 4) {
            return "Lecture impossible : coins trouvés = ${corners.keys.joinToString().ifEmpty { "aucun" }}."
        }
        val values = CORNER_IDS.associate { id -> propKey(id) to corners.getValue(id).toString() }
        val replaced = repository.savePosition(KeystonePosition(name, values, System.currentTimeMillis()))
        val detail = CORNER_IDS.joinToString(" · ") { "${CORNER_LABELS.getValue(it)} ${corners.getValue(it)}" }
        return "Position « $name » ${if (replaced) "remplacée" else "enregistrée"} ($detail)."
    }

    // ------------------------------------------------------------------
    // Appliquer une position (en arrière-plan, l'app passe derrière le menu XGIMI)
    // ------------------------------------------------------------------

    /** Retourne false si une application est déjà en cours. */
    fun applyPosition(scope: CoroutineScope, position: KeystonePosition): Boolean {
        if (_busy.value) return false
        _busy.value = true
        scope.launch(Dispatchers.IO) {
            try {
                _status.value = runApply(position)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MenuNotRespondingException) {
                repository.updateConfig { it.copy(menuComponent = "") }
                _status.value = "« ${position.name} » : ${e.message} Ouvre une fois le menu avec " +
                    "« Ouvrir le réglage trapèze » pour que l'app le réapprenne."
            } catch (e: Exception) {
                _status.value = "« ${position.name} » : échec – ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = false
            }
        }
        return true
    }

    private suspend fun runApply(position: KeystonePosition): String {
        ensureAdb()
        val targets = CORNER_IDS.mapNotNull { id ->
            position.values[propKey(id)]?.let { parseXY(it) }?.let { id to it }
        }.toMap()
        if (targets.isEmpty()) return "« ${position.name} » ne contient aucune position de coin : enregistre-la de nouveau."

        val config = repository.currentConfig()
        if (config.menuComponent.isBlank()) {
            return "Ouvre d'abord une fois le menu avec « Ouvrir le réglage trapèze » pour que l'app le retienne."
        }

        val start = readCorners()
        if (start.size < 4) return "Positions des coins illisibles (trouvés : ${start.keys.joinToString()})."
        if (targets.all { (id, xy) -> start[id] == xy }) return "« ${position.name} » est déjà appliquée."

        _status.value = "« ${position.name} » : ouverture du réglage trapèze…"
        if (!tryOpen(config.menuComponent)) {
            throw MenuNotRespondingException("impossible d'ouvrir le menu ${config.menuComponent}.")
        }
        delay(OPEN_SETTLE_MS)

        val corners = readCorners().toMutableMap()
        val order = config.cornerOrder.toMutableList()
        val inverted = config.inverted.toMutableSet()

        for (slot in 0 until 4) {
            currentCoroutineContext().ensureActive()
            val expected = order.getOrNull(slot)
            val alreadyOk = expected != null && (targets[expected] ?: corners[expected]) == corners[expected]
            val corner = if (alreadyOk && expected != null) expected else identifySelected(corners)
            if (order.size > slot) order[slot] = corner else order.add(corner)

            val target = targets[corner] ?: start.getValue(corner)
            _status.value = "« ${position.name} » : coin ${CORNER_LABELS.getValue(corner)} (${slot + 1}/4)…"
            moveAxis(corner, target.x, AXIS_X, corners, inverted)
            moveAxis(corner, target.y, AXIS_Y, corners, inverted)

            if (slot < 3) {
                press(KEY_OK, 1)
                delay(OK_SETTLE_MS)
                corners.putAll(readCorners())
            }
        }
        press(KEY_BACK, 1) // validation

        repository.updateConfig { it.copy(cornerOrder = order, inverted = inverted) }

        delay(OK_SETTLE_MS)
        val final = readCorners()
        val deviation = targets.maxOf { (id, xy) ->
            val f = final[id] ?: return@maxOf 0
            max(abs(f.x - xy.x), abs(f.y - xy.y))
        }
        return if (deviation == 0) {
            "Position « ${position.name} » appliquée."
        } else {
            "Position « ${position.name} » appliquée (écart résiduel max : $deviation)."
        }
    }

    /** Envoie une touche test et identifie le coin qui a bougé = coin actuellement sélectionné. */
    private suspend fun identifySelected(corners: MutableMap<String, XY>): String {
        val before = readCorners()
        press(KEY_RIGHT, 1)
        delay(KEY_SETTLE_MS)
        var after = readCorners()
        var moved = changedCorner(before, after)
        if (moved == null) { // coin peut-être en butée à droite
            press(KEY_LEFT, 1)
            delay(KEY_SETTLE_MS)
            val again = readCorners()
            moved = changedCorner(after, again)
            after = again
        }
        corners.putAll(after)
        return moved ?: throw MenuNotRespondingException("le menu trapèze ne réagit pas aux flèches.")
    }

    private fun changedCorner(before: Map<String, XY>, after: Map<String, XY>): String? =
        CORNER_IDS.firstOrNull { id -> before[id] != null && after[id] != null && before[id] != after[id] }

    /** Boucle fermée sur un axe : envoie des flèches, relit, corrige, jusqu'à la valeur cible. */
    private suspend fun moveAxis(corner: String, target: Int, axis: Int, corners: MutableMap<String, XY>, inverted: MutableSet<String>) {
        val invKey = "${corner}_$axis"
        var step = 1.0
        var noMove = 0
        var flips = 0
        repeat(MAX_ITERATIONS) {
            val current = corners[corner]?.get(axis) ?: throw IllegalStateException("coin $corner illisible.")
            val diff = target - current
            if (diff == 0) return
            if (step > 1.0 && abs(diff) < step / 2) return // cible entre deux pas : au plus près

            val increaseKey = increaseKey(axis, invKey in inverted)
            val key = if (diff > 0) increaseKey else opposite(increaseKey)
            val count = (abs(diff) / step).roundToInt().coerceIn(1, MAX_BATCH)
            press(key, count)
            delay(KEY_SETTLE_MS)

            val after = readCorners()
            corners.putAll(after)
            val updated = after[corner]?.get(axis) ?: throw IllegalStateException("coin $corner illisible.")
            val moved = updated - current
            if (moved == 0) {
                noMove++
                if (noMove >= 2) return // butée atteinte
                return@repeat
            }
            noMove = 0
            if (moved.sign != diff.sign) { // sens des flèches inversé pour cet axe : on l'apprend
                if (invKey in inverted) inverted.remove(invKey) else inverted.add(invKey)
                return@repeat
            }
            step = max(1.0, abs(moved).toDouble() / count)
            val newDiff = target - updated
            if (newDiff != 0 && newDiff.sign != diff.sign) {
                flips++
                if (flips >= 4) return
            }
        }
    }

    /** Hypothèse de départ : DROITE augmente X, BAS augmente Y (corrigée automatiquement si fausse). */
    private fun increaseKey(axis: Int, invertedAxis: Boolean): Int {
        val base = if (axis == AXIS_X) KEY_RIGHT else KEY_DOWN
        return if (invertedAxis) opposite(base) else base
    }

    private fun opposite(key: Int): Int = when (key) {
        KEY_RIGHT -> KEY_LEFT
        KEY_LEFT -> KEY_RIGHT
        KEY_UP -> KEY_DOWN
        else -> KEY_UP
    }

    // ------------------------------------------------------------------
    // Outils ADB
    // ------------------------------------------------------------------

    private suspend fun ensureAdb() {
        val state = adb.ensureConnected()
        if (state !is AdbState.Connected) throw IllegalStateException("ADB non connecté : ${adb.describe(state)}")
    }

    private suspend fun readCorners(): Map<String, XY> {
        val out = adb.shell("getprop | grep vendor.mstar.pos_").output
        return CORNER_REGEX.findAll(out).associate { m ->
            m.groupValues[1] to XY(m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
    }

    private fun parseXY(raw: String): XY? {
        val parts = raw.trim().split(':')
        if (parts.size != 2) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        return XY(x, y)
    }

    private suspend fun press(keyCode: Int, count: Int) {
        val codes = List(count) { keyCode }.joinToString(" ")
        val response = adb.shell("input keyevent $codes", retry = false)
        if (response.exitCode != 0) {
            throw IllegalStateException("envoi de touche refusé : ${response.allOutput.trim()}")
        }
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

    /** Parmi les écrans visités, celui qui est le plus probablement le réglage trapèze. */
    private suspend fun pickMenuComponent(seen: List<String>): String? {
        val home = homePackage()
        val candidates = seen.filter {
            val pkg = it.substringBefore('/')
            pkg != ownPackage && pkg != home && pkg != "com.android.systemui"
        }
        return candidates.lastOrNull { MENU_HINT_REGEX.containsMatchIn(it) } ?: candidates.lastOrNull()
    }
}
