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
 *  - « Ouvrir le réglage trapèze » : ouvre directement le menu de correction manuelle ;
 *  - « Enregistrer la position actuelle » : lit les 4 coins et les enregistre sous un nom ;
 *  - un clic sur un nom : applique la position (pilotage automatique du menu XGIMI).
 */
class MainActivity : Activity() {

    private val scope = MainScope()

    private lateinit var dotAdb: View
    private lateinit var txtAdbStatus: TextView
    private lateinit var btnTestAdb: Button
    private lateinit var btnOpenMenu: Button
    private lateinit var btnSavePosition: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var positionContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dotAdb = findViewById(R.id.dotAdb)
        txtAdbStatus = findViewById(R.id.txtAdbStatus)
        btnTestAdb = findViewById(R.id.btnTestAdb)
        btnOpenMenu = findViewById(R.id.btnOpenMenu)
        btnSavePosition = findViewById(R.id.btnSavePosition)
        txtStatus = findViewById(R.id.txtStatus)
        txtEmpty = findViewById(R.id.txtEmpty)
        positionContainer = findViewById(R.id.positionContainer)

        btnTestAdb.setOnClickListener { scope.launch { app.adb.testConnection() } }
        btnOpenMenu.setOnClickListener { openMenu() }
        btnSavePosition.setOnClickListener { showSaveDialog() }

        scope.launch { app.adb.state.collect { renderAdb(it) } }
        scope.launch { app.repository.positions.collect { renderPositions(it) } }
        scope.launch { app.engine.status.collect { if (it.isNotEmpty()) txtStatus.text = it } }
        scope.launch {
            app.engine.busy.collect { busy ->
                btnOpenMenu.isEnabled = !busy
                btnSavePosition.isEnabled = !busy
            }
        }

        btnOpenMenu.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (app.adb.state.value is AdbState.Unknown) {
            scope.launch { app.adb.testConnection() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- Actions ----------------

    private fun openMenu() {
        txtStatus.text = "Ouverture du réglage trapèze…"
        scope.launch {
            val message = try {
                app.engine.openMenu(app.appScope)
            } catch (e: Exception) {
                "Ouverture impossible : ${e.message ?: e.javaClass.simpleName}"
            }
            txtStatus.text = message
            toast(message)
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
            .setTitle("Enregistrer la position actuelle")
            .setMessage("Donne un nom à la correction trapézoïdale actuelle (un nom existant est remplacé).")
            .setView(container)
            .setPositiveButton("Enregistrer", null)
            .setNegativeButton("Annuler", null)
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
                app.engine.saveCurrent(name)
            } catch (e: Exception) {
                "Échec de l'enregistrement : ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun applyPosition(position: KeystonePosition) {
        if (!app.engine.applyPosition(app.appScope, position)) {
            toast("Une position est déjà en cours d'application.")
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
