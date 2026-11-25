package com.keystoneswitcher.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.keystoneswitcher.R
import com.keystoneswitcher.guard.ExecutionController.RunState

/** Petits constructeurs de vues stylées, focalisables au D-Pad. */

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.kButton(label: String, onClick: () -> Unit): Button =
    Button(this, null, 0, R.style.KButton).apply {
        text = label
        setOnClickListener { onClick() }
    }

fun Context.kInput(hintText: String = ""): EditText =
    EditText(this, null, 0, R.style.KInput).apply { hint = hintText }

fun Context.kText(content: String, styleRes: Int): TextView =
    TextView(this, null, 0, styleRes).apply { text = content }

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

fun View.setDotColor(colorRes: Int) {
    background?.mutate()?.setTint(context.getColor(colorRes))
}

fun RunState.describe(): String = when (this) {
    RunState.Idle -> "Aucune séquence en cours."
    is RunState.Running ->
        if (total == 0) "$label : $detail" else "$label : étape $done/$total – $detail (Retour = annuler)"
    is RunState.Finished -> "$label : terminé."
    is RunState.Aborted -> "$label : ANNULÉ – $reason"
    is RunState.Failed -> "$label : ÉCHEC – $message"
}
