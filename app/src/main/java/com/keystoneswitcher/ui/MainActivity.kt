package com.keystoneswitcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.keystoneswitcher.R
import com.keystoneswitcher.adb.AdbState
import com.keystoneswitcher.app
import com.keystoneswitcher.data.KeystonePosition
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Écran unique :
 *  - « Nouvelle position » : ouvre le réglage trapèze, puis propose d'enregistrer la position au retour ;
 *  - un clic sur une position enregistrée : l'applique.
 */
class MainActivity : Activity() {

    private val scope = MainScope()

    private lateinit var dotAdb: View
    private lateinit var txtAdbStatus: TextView
    private lateinit var btnTestAdb: Button
    private lateinit var btnNewPosition: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var positionContainer: LinearLayout

    /** true entre le clic sur « Nouvelle position » et le retour dans l'app. */
    private var awaitingReturn = false
    private var leftApp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dotAdb = findViewById(R.id.dotAdb)
        txtAdbStatus = findViewById(R.id.txtAdbStatus)
        btnTestAdb = findViewById(R.id.btnTestAdb)
        btnNewPosition = findViewById(R.id.btnNewPosition)
        txtStatus = findViewById(R.id.txtStatus)
        txtEmpty = findViewById(R.id.txtEmpty)
        positionContainer = findViewById(R.id.positionContainer)

        btnTestAdb.setOnClickListener { scope.launch { app.adb.testConnection() } }
        btnNewPosition.setOnClickListener { startNewPosition() }

        scope.launch { app.adb.state.collect { renderAdb(it) } }
        scope.launch { app.repository.positions.collect { renderPositions(it) } }

        btnNewPosition.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (app.adb.state.value is AdbState.Unknown) {
            scope.launch { app.adb.testConnection() }
        }
        if (awaitingReturn && leftApp) {
            awaitingReturn = false
            leftApp = false
            if (app.engine.hasPendingSession) showSaveDialog()
        }
    }

    override fun onPause() {
        super.onPause()
        if (awaitingReturn) leftApp = true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- Actions ----------------

    private fun startNewPosition() {
        awaitingReturn = true
        leftApp = false
        txtStatus.text = "Préparation (lecture des réglages actuels)…"
        btnNewPosition.isEnabled = false
        scope.launch {
            try {
                val message = app.engine.startNewPosition(app.appScope)
                txtStatus.text = message
                toast(message)
            } catch (e: Exception) {
                awaitingReturn = false
                txtStatus.text = "Impossible de démarrer : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                btnNewPosition.isEnabled = true
            }
        }
    }

    private fun showSaveDialog() {
        val input = kInput("Nom (ex. Mur salon, Plafond, Bureau)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enregistrer cette position ?")
            .setMessage("Donne un nom à la correction trapézoïdale actuelle.")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Enregistrer", null)
            .setNegativeButton("Annuler") { _, _ ->
                app.engine.cancelSession()
                txtStatus.text = "Enregistrement annulé."
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Saisis un nom.")
                } else {
                    dialog.dismiss()
                    savePosition(name)
                }
            }
            input.requestFocus()
        }
        dialog.show()
    }

    private fun savePosition(name: String) {
        txtStatus.text = "Enregistrement de « $name »…"
        scope.launch {
            txtStatus.text = try {
                app.engine.finishSession(name)
            } catch (e: Exception) {
                "Échec de l'enregistrement : ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun applyPosition(position: KeystonePosition) {
        txtStatus.text = "Application de « ${position.name} »…"
        scope.launch {
            txtStatus.text = try {
                app.engine.applyPosition(position)
            } catch (e: Exception) {
                "Échec : ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun confirmDelete(position: KeystonePosition) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer « ${position.name} » ?")
            .setPositiveButton("Supprimer") { _, _ ->
                scope.launch { app.repository.deletePosition(position.name) }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------------- Affichage ----------------

    private fun renderAdb(state: AdbState) {
        txtAdbStatus.text = "ADB : " + app.adb.describe(state)
        val color = when (state) {
            is AdbState.Connected -> R.color.state_ok
            AdbState.Checking, AdbState.AuthPending -> R.color.state_warn
            AdbState.Unknown -> R.color.state_idle
            else -> R.color.state_error
        }
        dotAdb.setDotColor(color)
        btnTestAdb.isEnabled = state !is AdbState.Checking && state !is AdbState.AuthPending
    }

    private fun renderPositions(positions: List<KeystonePosition>) {
        positionContainer.removeAllViews()
        txtEmpty.visibility = if (positions.isEmpty()) View.VISIBLE else View.GONE
        positions.forEach { position ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val applyButton = kButton(position.name) { applyPosition(position) }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textSize = 18f
            }
            val deleteButton = kButton("Supprimer") { confirmDelete(position) }
            row.addView(applyButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                deleteButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(12) }
            )
            positionContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
        }
    }
}
