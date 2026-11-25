package com.keystoneswitcher.data

/**
 * Saisie des séquences de touches : codes numériques (ex. "20,20,23")
 * ou alias lisibles (ex. "BAS,BAS,OK"). Séparateurs acceptés : virgule, point-virgule, espace.
 */
object KeySequence {

    private val ALIASES = mapOf(
        "UP" to 19, "HAUT" to 19,
        "DOWN" to 20, "BAS" to 20,
        "LEFT" to 21, "GAUCHE" to 21,
        "RIGHT" to 22, "DROITE" to 22,
        "OK" to 23, "CENTER" to 23,
        "BACK" to 4, "RETOUR" to 4,
        "HOME" to 3, "MENU" to 82, "SETTINGS" to 176
    )

    private val NAMES = mapOf(
        19 to "HAUT", 20 to "BAS", 21 to "GAUCHE", 22 to "DROITE", 23 to "OK",
        4 to "RETOUR", 3 to "HOME", 82 to "MENU", 176 to "SETTINGS"
    )

    /** @throws IllegalArgumentException si un élément n'est ni un alias ni un keycode 1..400. */
    fun parse(text: String): List<Int> {
        val tokens = text.trim().split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
        return tokens.map { token ->
            ALIASES[token.uppercase()]
                ?: token.toIntOrNull()?.takeIf { it in 1..400 }
                ?: throw IllegalArgumentException("Touche inconnue : « $token »")
        }
    }

    fun format(keys: List<Int>): String = keys.joinToString(",")

    fun label(code: Int): String = NAMES[code] ?: code.toString()

    fun describe(keys: List<Int>): String =
        if (keys.isEmpty()) "—" else keys.joinToString(" → ") { label(it) }
}
