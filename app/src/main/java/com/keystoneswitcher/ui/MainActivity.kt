package com.keystoneswitcher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.keystoneswitcher.R
import com.keystoneswitcher.adb.AdbState
import com.keystoneswitcher.app
import com.keystoneswitcher.data.KeystoneProfile
import com.keystoneswitcher.guard.ExecutionController
import com.keystoneswitcher.guard.KeystoneGuardService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val scope = MainScope()

    private lateinit var dotAdb: View
    private lateinit var txtAdbStatus: TextView
    private lateinit var btnTestAdb: Button
    private lateinit var dotGuard: View
    private lateinit var txtGuardStatus: TextView
    private lateinit var txtRunState: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var profileContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dotAdb = findViewById(R.id.dotAdb)
        txtAdbStatus = findViewById(R.id.txtAdbStatus)
        btnTestAdb = findViewById(R.id.btnTestAdb)
        dotGuard = findViewById(R.id.dotGuard)
        txtGuardStatus = findViewById(R.id.txtGuardStatus)
        txtRunState = findViewById(R.id.txtRunState)
        txtEmpty = findViewById(R.id.txtEmpty)
        profileContainer = findViewById(R.id.profileContainer)

        btnTestAdb.setOnClickListener {
            if (ExecutionController.isRunning) {
                toast("Séquence en cours : attends la fin ou appuie sur une touche de la télécommande.")
            } else {
                scope.launch { app.executor.testConnection() }
            }
        }
        findViewById<Button>(R.id.btnGuardSettings).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btnGuardAdb).setOnClickListener {
            scope.launch {
                val message = try {
                    app.executor.enableGuardViaAdb()
                } catch (e: Exception) {
                    "Échec de l'activation : ${e.message ?: e.javaClass.simpleName}"
                }
                toast(message)
                delay(1_500)
                renderGuard()
            }
        }
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        findViewById<Button>(R.id.btnNewProfile).setOnClickListener {
            startActivity(Intent(this, ProfileEditActivity::class.java))
        }

        scope.launch { app.repository.profiles.collect { renderProfiles(it) } }
        scope.launch { app.executor.adbState.collect { renderAdb(it) } }
        scope.launch { ExecutionController.guardConnected.collect { renderGuard() } }
        scope.launch { ExecutionController.state.collect { txtRunState.text = it.describe() } }

        btnTestAdb.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        renderGuard()
        if (app.executor.adbState.value is AdbState.Unknown) {
            scope.launch { app.executor.testConnection() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun renderAdb(state: AdbState) {
        txtAdbStatus.text = "ADB : " + app.executor.describe(state)
        val color = when (state) {
            is AdbState.Connected -> R.color.state_ok
            AdbState.Checking, AdbState.AuthPending -> R.color.state_warn
            AdbState.Unknown -> R.color.state_idle
            else -> R.color.state_error
        }
        dotAdb.setDotColor(color)
        btnTestAdb.isEnabled = state !is AdbState.Checking && state !is AdbState.AuthPending
        renderGuard()
    }

    /**
     * Coupe-circuit principal = lecture de la télécommande via ADB (getevent) : il suit l'état ADB.
     * Le service d'accessibilité n'est plus qu'un observateur complémentaire, optionnel.
     */
    private fun renderGuard() {
        val adbReady = app.executor.adbState.value is AdbState.Connected
        val accessibility = when {
            ExecutionController.guardConnected.value -> "accessibilité active"
            KeystoneGuardService.isEnabledInSettings(this) -> "accessibilité en attente"
            else -> "accessibilité inactive (optionnelle)"
        }
        val (text, color) = if (adbReady) {
            "Coupe-circuit prêt : toute touche de la télécommande arrête la séquence · $accessibility" to R.color.state_ok
        } else {
            "Coupe-circuit indisponible tant qu'ADB n'est pas connecté · $accessibility" to R.color.state_error
        }
        txtGuardStatus.text = text
        dotGuard.setDotColor(color)
    }

    private fun renderProfiles(profiles: List<KeystoneProfile>) {
        profileContainer.removeAllViews()
        txtEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        profiles.forEach { profile ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val summary = "${profile.profileName}     ·  ${profile.totalKeyPresses()} clics  ·  ${profile.stepDelayMs} ms"
            val runButton = kButton(summary) { runProfile(profile) }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            val editButton = kButton("Modifier") {
                startActivity(
                    Intent(this, ProfileEditActivity::class.java)
                        .putExtra(ProfileEditActivity.EXTRA_PROFILE_NAME, profile.profileName)
                )
            }
            row.addView(runButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                editButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(12) }
            )
            profileContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
        }
    }

    private fun runProfile(profile: KeystoneProfile) {
        val error = app.executor.runProfile(app.appScope, profile)
        if (error != null) toast(error)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            toast("Écran Accessibilité introuvable sur ce système : utilise « Activer via ADB ».")
        }
    }
}
