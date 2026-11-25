package com.keystoneswitcher.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

enum class Corner(val jsonKey: String, val label: String) {
    TOP_LEFT("topLeft", "Haut-Gauche"),
    TOP_RIGHT("topRight", "Haut-Droite"),
    BOTTOM_LEFT("bottomLeft", "Bas-Gauche"),
    BOTTOM_RIGHT("bottomRight", "Bas-Droite")
}

/**
 * Décalage d'un coin en nombre de clics.
 * x > 0 : DROITE (22) ; x < 0 : GAUCHE (21) ; y > 0 : HAUT (19) ; y < 0 : BAS (20).
 */
data class CornerOffset(val x: Int = 0, val y: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("x", x).put("y", y)

    companion object {
        fun fromJson(o: JSONObject?): CornerOffset =
            if (o == null) CornerOffset() else CornerOffset(o.optInt("x", 0), o.optInt("y", 0))
    }
}

data class KeystoneProfile(
    val profileName: String,
    val stepDelayMs: Long = DEFAULT_STEP_DELAY_MS,
    val corners: Map<Corner, CornerOffset> = Corner.values().associateWith { CornerOffset() }
) {
    fun offset(corner: Corner): CornerOffset = corners[corner] ?: CornerOffset()

    fun totalKeyPresses(): Int = Corner.values().sumOf { abs(offset(it).x) + abs(offset(it).y) }

    /** Format JSON identique à l'exemple de spécification. */
    fun toJson(): JSONObject {
        val cornersJson = JSONObject()
        Corner.values().forEach { cornersJson.put(it.jsonKey, offset(it).toJson()) }
        return JSONObject()
            .put("profileName", profileName)
            .put("stepDelayMs", stepDelayMs)
            .put("corners", cornersJson)
    }

    companion object {
        const val DEFAULT_STEP_DELAY_MS = 300L

        fun fromJson(o: JSONObject): KeystoneProfile {
            val c = o.optJSONObject("corners")
            return KeystoneProfile(
                profileName = o.getString("profileName"),
                stepDelayMs = o.optLong("stepDelayMs", DEFAULT_STEP_DELAY_MS),
                corners = Corner.values().associateWith { CornerOffset.fromJson(c?.optJSONObject(it.jsonKey)) }
            )
        }

        fun listToJson(list: List<KeystoneProfile>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString(2)
        }

        fun listFromJson(raw: String?): List<KeystoneProfile> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

enum class LaunchMode(val label: String) {
    ACTIVITY("Activité (am start -n)"),
    KEY_SEQUENCE("Séquence de touches")
}

/** Paramètres propres au projecteur, renseignés par l'auto-détection ou manuellement. */
data class DeviceConfig(
    val targetComponent: String = "",          // package/activité du menu trapèze
    val watchPackage: String = "",             // package surveillé par le coupe-circuit
    val launchMode: LaunchMode = LaunchMode.ACTIVITY,
    val openSequence: List<Int> = emptyList(), // utilisé en mode KEY_SEQUENCE
    val launchSettleMs: Long = 1500L,
    val restoreSequence: List<Int> = emptyList(), // Keystone Restore (vide = étape ignorée)
    val restoreSettleMs: Long = 800L,
    val cornerSelect: Map<Corner, List<Int>> = emptyMap(), // touches pour sélectionner un coin
    val cornerCommit: Map<Corner, List<Int>> = emptyMap(), // touches pour valider un coin
    val finishSequence: List<Int> = emptyList(),
    val foregroundGuard: Boolean = true,
    val lastDetectionReport: String = ""
) {
    fun selectFor(corner: Corner): List<Int> = cornerSelect[corner].orEmpty()
    fun commitFor(corner: Corner): List<Int> = cornerCommit[corner].orEmpty()

    fun toJson(): JSONObject {
        val select = JSONObject()
        val commit = JSONObject()
        Corner.values().forEach {
            select.put(it.jsonKey, selectFor(it).toJsonArray())
            commit.put(it.jsonKey, commitFor(it).toJsonArray())
        }
        return JSONObject()
            .put("targetComponent", targetComponent)
            .put("watchPackage", watchPackage)
            .put("launchMode", launchMode.name)
            .put("openSequence", openSequence.toJsonArray())
            .put("launchSettleMs", launchSettleMs)
            .put("restoreSequence", restoreSequence.toJsonArray())
            .put("restoreSettleMs", restoreSettleMs)
            .put("cornerSelect", select)
            .put("cornerCommit", commit)
            .put("finishSequence", finishSequence.toJsonArray())
            .put("foregroundGuard", foregroundGuard)
            .put("lastDetectionReport", lastDetectionReport)
    }

    companion object {
        fun fromJsonString(raw: String?): DeviceConfig {
            if (raw.isNullOrBlank()) return DeviceConfig()
            return try {
                val o = JSONObject(raw)
                val select = o.optJSONObject("cornerSelect")
                val commit = o.optJSONObject("cornerCommit")
                DeviceConfig(
                    targetComponent = o.optString("targetComponent", ""),
                    watchPackage = o.optString("watchPackage", ""),
                    launchMode = runCatching { LaunchMode.valueOf(o.optString("launchMode")) }
                        .getOrDefault(LaunchMode.ACTIVITY),
                    openSequence = o.optJSONArray("openSequence").toIntList(),
                    launchSettleMs = o.optLong("launchSettleMs", 1500L),
                    restoreSequence = o.optJSONArray("restoreSequence").toIntList(),
                    restoreSettleMs = o.optLong("restoreSettleMs", 800L),
                    cornerSelect = Corner.values().associateWith { select?.optJSONArray(it.jsonKey).toIntList() },
                    cornerCommit = Corner.values().associateWith { commit?.optJSONArray(it.jsonKey).toIntList() },
                    finishSequence = o.optJSONArray("finishSequence").toIntList(),
                    foregroundGuard = o.optBoolean("foregroundGuard", true),
                    lastDetectionReport = o.optString("lastDetectionReport", "")
                )
            } catch (e: Exception) {
                DeviceConfig()
            }
        }
    }
}

private fun List<Int>.toJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { array.put(it) }
    return array
}

private fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return (0 until length()).map { getInt(it) }
}
