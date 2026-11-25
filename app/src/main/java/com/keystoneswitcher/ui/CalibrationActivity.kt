package com.keystoneswitcher.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.keystoneswitcher.R
import com.keystoneswitcher.adb.DadbKeystoneExecutor
import com.keystoneswitcher.adb.DetectionState
import com.keystoneswitcher.app
import com.keystoneswitcher.data.Corner
import com.keystoneswitcher.data.DeviceConfig
import com.keystoneswitcher.data.KeySequence
import com.keystoneswitcher.data.LaunchMode
import com.keystoneswitcher.guard.ExecutionController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Calibration : auto-détection de l'activité trapèze XGIMI + réglage manuel
 * des séquences (ouverture, remise à zéro, sélection/validation des coins, fin).
 */
class CalibrationActivity : Activity() {

    private val scope = MainScope()
    private var loadedConfig = DeviceConfig()
    private var launchMode = LaunchMode.ACTIVITY
    private var foregroundGuard = true

    private lateinit var txtDetectState: TextView
    private lateinit var txtReport: TextView
    private lateinit var txtStatus: TextView

    private lateinit var edtComponent: EditText
    private lateinit var edtWatch: EditText
    private lateinit var btnMode: Button
    private lateinit var edtOpenSequence: EditText
    private lateinit var edtLaunchSettle: EditText
    private lateinit var edtRestoreSequence: EditText
    private lateinit var edtRestoreSettle: EditText
    private val edtSelect = mutableMapOf<Corner, EditText>()
    private val edtCommit = mutableMapOf<Corner, EditText>()
    private lateinit var edtFinish: EditText
    private lateinit var btnGuardToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        txtDetectState = findViewById(R.id.txtDetectState)
        txtReport = findViewById(R.id.txtReport)
        txtStatus = findViewById(R.id.txtCalibrationStatus)
        buildForm(findViewById(R.id.formContainer))

        findViewById<Button>(R.id.btnDetect).setOnClickListener {
            app.executor.startDetection(app.appScope)
        }
        findViewById<Button>(R.id.btnSaveConfig).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.btnTestOpen).setOnClickListener { runTest(includeRestore = false) }
        findViewById<Button>(R.id.btnTestRestore).setOnClickListener { runTest(includeRestore = true) }

        scope.launch { populate(app.repository.currentDeviceConfig()) }
        scope.launch {
            app.executor.detectionState.collect { state ->
                txtDetectState.text = describe(state)
                if (state is DetectionState.Done || state is DetectionState.Failed) {
                    // Recharge la configuration enregistrée par la détection
                    populate(app.repository.currentDeviceConfig())
                }
            }
        }
        scope.launch { ExecutionController.state.collect { txtStatus.text = it.describe() } }
        findViewById<Button>(R.id.btnDetect).requestFocus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- Formulaire ----------------

    private fun buildForm(container: LinearLayout) {
        edtComponent = addField(container, "Activité du menu trapèze (package/activité)", "com.exemple/.KeystoneActivity")
        edtWatch = addField(container, "Package surveillé par le coupe-circuit", "com.exemple")

        btnMode = kButton("") {
            launchMode = if (launchMode == LaunchMode.ACTIVITY) LaunchMode.KEY_SEQUENCE else LaunchMode.ACTIVITY
            renderToggles()
        }
        container.addView(btnMode, blockParams())

        edtOpenSequence = addField(container, "Séquence d'ouverture (mode séquence uniquement)", "ex. SETTINGS,BAS,OK")
        edtLaunchSettle = addField(container, "Attente après ouverture (ms)", "1500", numeric = true)
        edtRestoreSequence = addField(container, "Séquence de remise à zéro (Keystone Restore)", "ex. BAS,BAS,OK,OK")
        edtRestoreSettle = addField(container, "Attente après remise à zéro (ms)", "800", numeric = true)

        Corner.values().forEach { corner ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            edtSelect[corner] = addField(left, "${corner.label} : touches de sélection", "ex. OK")
            edtCommit[corner] = addField(right, "${corner.label} : touches de validation", "ex. OK")
            row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                right,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) }
            )
            container.addView(row, blockParams())
        }

        edtFinish = addField(container, "Séquence de fin (après les 4 coins)", "ex. RETOUR")

        btnGuardToggle = kButton("") {
            foregroundGuard = !foregroundGuard
            renderToggles()
        }
        container.addView(btnGuardToggle, blockParams())
    }

    private fun addField(container: LinearLayout, label: String, hint: String, numeric: Boolean = false): EditText {
        container.addView(
            kText(label, R.style.KHint),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )
        val input = kInput(hint).apply {
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }
        container.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        return input
    }

    private fun blockParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(14) }

    private fun renderToggles() {
        btnMode.text = "Mode d'ouverture : ${launchMode.label}"
        btnGuardToggle.text =
            if (foregroundGuard) "Vérification du premier plan : ACTIVÉE" else "Vérification du premier plan : désactivée"
    }

    private fun populate(config: DeviceConfig) {
        loadedConfig = config
        launchMode = config.launchMode
        foregroundGuard = config.foregroundGuard
        edtComponent.setText(config.targetComponent)
        edtWatch.setText(config.watchPackage)
        edtOpenSequence.setText(KeySequence.format(config.openSequence))
        edtLaunchSettle.setText(config.launchSettleMs.toString())
        edtRestoreSequence.setText(KeySequence.format(config.restoreSequence))
        edtRestoreSettle.setText(config.restoreSettleMs.toString())
        Corner.values().forEach { corner ->
            edtSelect[corner]?.setText(KeySequence.format(config.selectFor(corner)))
            edtCommit[corner]?.setText(KeySequence.format(config.commitFor(corner)))
        }
        edtFinish.setText(KeySequence.format(config.finishSequence))
        txtReport.text = config.lastDetectionReport.ifEmpty { "Aucune détection enregistrée." }
        renderToggles()
    }

    /** Lit et valide le formulaire ; null (avec message) si une saisie est invalide. */
    private fun readForm(): DeviceConfig? {
        return try {
            val component = edtComponent.text.toString().trim()
            require(component.isEmpty() || component.contains('/')) {
                "Activité : format attendu package/activité."
            }
            DeviceConfig(
                targetComponent = component,
                watchPackage = edtWatch.text.toString().trim(),
                launchMode = launchMode,
                openSequence = parseField("Séquence d'ouverture", edtOpenSequence),
                launchSettleMs = parseDelay("Attente après ouverture", edtLaunchSettle),
                restoreSequence = parseField("Remise à zéro", edtRestoreSequence),
                restoreSettleMs = parseDelay("Attente après remise à zéro", edtRestoreSettle),
                cornerSelect = Corner.values().associateWith { c ->
                    parseField("${c.label} sélection", edtSelect.getValue(c))
                },
                cornerCommit = Corner.values().associateWith { c ->
                    parseField("${c.label} validation", edtCommit.getValue(c))
                },
                finishSequence = parseField("Séquence de fin", edtFinish),
                foregroundGuard = foregroundGuard,
                lastDetectionReport = loadedConfig.lastDetectionReport
            )
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "Saisie invalide.")
            null
        }
    }

    private fun parseField(name: String, field: EditText): List<Int> = try {
        KeySequence.parse(field.text.toString())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$name : ${e.message}")
    }

    private fun parseDelay(name: String, field: EditText): Long {
        val value = field.text.toString().trim().toLongOrNull()
        if (value == null || value !in 0L..15_000L) {
            throw IllegalArgumentException("$name : valeur entre 0 et 15000 ms attendue.")
        }
        return value
    }

    private fun saveConfig() {
        val config = readForm() ?: return
        scope.launch {
            app.repository.saveDeviceConfig(config)
            loadedConfig = config
            toast("Configuration XGIMI enregistrée.")
        }
    }

    private fun runTest(includeRestore: Boolean) {
        val config = readForm() ?: return
        val error = app.executor.runCalibrationTest(app.appScope, config, includeRestore)
        if (error != null) toast(error)
    }

    private fun describe(state: DetectionState): String = when (state) {
        DetectionState.Idle -> "Prêt."
        DetectionState.CheckingAdb -> "Vérification de la connexion ADB…"
        is DetectionState.Countdown ->
            "Ouvre MAINTENANT le menu trapèze XGIMI : capture dans ${state.secondsLeft} s " +
                "(${DadbKeystoneExecutor.DETECTION_COUNTDOWN_S} s au total)"
        DetectionState.Capturing -> "Capture en cours (dumpsys + uiautomator)…"
        is DetectionState.Done -> state.summary
        is DetectionState.Failed -> state.message
    }
}
