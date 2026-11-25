package com.keystoneswitcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.keystoneswitcher.R
import com.keystoneswitcher.app
import com.keystoneswitcher.data.Corner
import com.keystoneswitcher.data.CornerOffset
import com.keystoneswitcher.data.KeystoneProfile
import com.keystoneswitcher.guard.ExecutionController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProfileEditActivity : Activity() {

    companion object {
        const val EXTRA_PROFILE_NAME = "profile_name"
        private const val MIN_CLICKS = -99
        private const val MAX_CLICKS = 99
        private const val AXIS_X = 0
        private const val AXIS_Y = 1
    }

    private val scope = MainScope()
    private var originalName: String? = null

    /** Décalages en cours d'édition : [x, y] par coin. */
    private val offsets: Map<Corner, IntArray> = Corner.values().associateWith { IntArray(2) }
    private val valueViews = mutableMapOf<Pair<Corner, Int>, TextView>()

    private lateinit var edtName: EditText
    private lateinit var edtDelay: EditText
    private lateinit var txtEditStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_edit)

        originalName = intent.getStringExtra(EXTRA_PROFILE_NAME)
        edtName = findViewById(R.id.edtName)
        edtDelay = findViewById(R.id.edtDelay)
        txtEditStatus = findViewById(R.id.txtEditStatus)
        edtDelay.setText(KeystoneProfile.DEFAULT_STEP_DELAY_MS.toString())

        buildCornerGrid(findViewById(R.id.cornerGrid))
        buildKeyTestRow(findViewById(R.id.keyTestRow))

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnTestProfile).setOnClickListener { testProfile() }
        val btnDelete = findViewById<Button>(R.id.btnDelete)
        btnDelete.visibility = if (originalName == null) View.GONE else View.VISIBLE
        btnDelete.setOnClickListener { confirmDelete() }

        val name = originalName
        if (name != null) {
            scope.launch {
                val profile = app.repository.currentProfiles().firstOrNull { it.profileName == name }
                if (profile != null) populate(profile)
            }
        }
        scope.launch { ExecutionController.state.collect { txtEditStatus.text = it.describe() } }
        edtName.requestFocus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- Construction du formulaire ----------------

    private fun buildCornerGrid(grid: LinearLayout) {
        listOf(
            listOf(Corner.TOP_LEFT, Corner.TOP_RIGHT),
            listOf(Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT)
        ).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, corner ->
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (index == 1) params.marginStart = dp(16)
                row.addView(buildCornerCard(corner), params)
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            )
        }
    }

    private fun buildCornerCard(corner: Corner): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(kText(corner.label, R.style.KSection))
        card.addView(buildAxisRow(corner, AXIS_X))
        card.addView(buildAxisRow(corner, AXIS_Y))
        return card
    }

    private fun buildAxisRow(corner: Corner, axis: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(
            kText(if (axis == AXIS_X) "X" else "Y", R.style.KBody),
            LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        listOf(-5, -1).forEach { delta -> row.addView(stepButton(corner, axis, delta), stepParams()) }
        val valueView = kText("0", R.style.KBody).apply { gravity = Gravity.CENTER }
        valueViews[corner to axis] = valueView
        row.addView(valueView, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        listOf(1, 5).forEach { delta -> row.addView(stepButton(corner, axis, delta), stepParams()) }
        return row
    }

    private fun stepButton(corner: Corner, axis: Int, delta: Int): Button {
        val label = if (delta > 0) "+$delta" else "−${-delta}"
        return kButton(label) { changeOffset(corner, axis, delta) }.apply {
            setPadding(dp(10), 0, dp(10), 0)
        }
    }

    private fun stepParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(6) }

    private fun buildKeyTestRow(row: LinearLayout) {
        listOf(
            "↑ HAUT" to 19,
            "↓ BAS" to 20,
            "← GAUCHE" to 21,
            "→ DROITE" to 22,
            "OK" to 23
        ).forEach { (label, code) ->
            row.addView(kButton(label) { sendSingleKey(code) }, stepParams())
        }
    }

    // ---------------- Logique ----------------

    private fun changeOffset(corner: Corner, axis: Int, delta: Int) {
        val values = offsets.getValue(corner)
        values[axis] = (values[axis] + delta).coerceIn(MIN_CLICKS, MAX_CLICKS)
        refreshValue(corner, axis)
    }

    private fun refreshValue(corner: Corner, axis: Int) {
        valueViews[corner to axis]?.text = offsets.getValue(corner)[axis].toString()
    }

    private fun populate(profile: KeystoneProfile) {
        edtName.setText(profile.profileName)
        edtDelay.setText(profile.stepDelayMs.toString())
        Corner.values().forEach { corner ->
            val o = profile.offset(corner)
            offsets.getValue(corner)[AXIS_X] = o.x
            offsets.getValue(corner)[AXIS_Y] = o.y
            refreshValue(corner, AXIS_X)
            refreshValue(corner, AXIS_Y)
        }
    }

    /** Construit le profil depuis le formulaire ; null (avec message) si la saisie est invalide. */
    private fun readProfile(): KeystoneProfile? {
        val name = edtName.text.toString().trim()
        if (name.isEmpty()) {
            toast("Donne un nom au profil.")
            return null
        }
        val delay = edtDelay.text.toString().trim().toLongOrNull()
        if (delay == null || delay !in 50L..5_000L) {
            toast("Délai invalide : saisis une valeur entre 50 et 5000 ms.")
            return null
        }
        return KeystoneProfile(
            profileName = name,
            stepDelayMs = delay,
            corners = Corner.values().associateWith { corner ->
                val v = offsets.getValue(corner)
                CornerOffset(v[AXIS_X], v[AXIS_Y])
            }
        )
    }

    private fun save() {
        val profile = readProfile() ?: return
        scope.launch {
            try {
                app.repository.saveProfile(profile, originalName)
                toast("Profil « ${profile.profileName} » enregistré.")
                finish()
            } catch (e: IllegalStateException) {
                toast(e.message ?: "Enregistrement impossible.")
            }
        }
    }

    private fun testProfile() {
        val profile = readProfile() ?: return
        val error = app.executor.runProfile(app.appScope, profile)
        if (error != null) toast(error)
    }

    private fun sendSingleKey(code: Int) {
        if (ExecutionController.isRunning) {
            toast("Séquence en cours : attends la fin ou appuie sur une touche de la télécommande.")
            return
        }
        scope.launch {
            try {
                app.executor.sendKey(code)
                txtEditStatus.text = "Touche $code envoyée via ADB."
            } catch (e: Exception) {
                toast("Envoi impossible : ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun confirmDelete() {
        val name = originalName ?: return
        AlertDialog.Builder(this)
            .setTitle("Supprimer « $name » ?")
            .setMessage("Cette suppression est définitive.")
            .setPositiveButton("Supprimer") { _, _ ->
                scope.launch {
                    app.repository.deleteProfile(name)
                    finish()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
