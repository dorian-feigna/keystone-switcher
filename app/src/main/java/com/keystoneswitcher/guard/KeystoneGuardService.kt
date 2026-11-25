package com.keystoneswitcher.guard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Observateur léger et coupe-circuit :
 *  - touche Retour PHYSIQUE (télécommande) pendant une séquence => annulation immédiate ;
 *  - changement de premier plan hors du package XGIMI surveillé => annulation immédiate.
 * Les touches injectées par `input keyevent` portent deviceId = VIRTUAL_KEYBOARD (-1) et sont ignorées.
 */
class KeystoneGuardService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ExecutionController.guardConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        ExecutionController.onForegroundChanged(pkg)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD &&
            ExecutionController.isRunning
        ) {
            ExecutionController.abort("Touche Retour pressée sur la télécommande")
        }
        // Ne jamais consommer la touche : Retour garde son comportement normal.
        return false
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ExecutionController.guardConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ExecutionController.guardConnected.value = false
        super.onDestroy()
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, KeystoneGuardService::class.java)

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = componentName(context)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
