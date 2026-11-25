package com.keystoneswitcher.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Position enregistrée = copie des réglages système qui stockent la correction trapézoïdale.
 * Clé : "<espace>:<nom>" avec espace = global | system | secure | prop.
 */
data class KeystonePosition(
    val name: String,
    val values: Map<String, String>,
    val savedAt: Long
) {
    fun toJson(): JSONObject {
        val v = JSONObject()
        values.forEach { (k, value) -> v.put(k, value) }
        return JSONObject().put("name", name).put("savedAt", savedAt).put("values", v)
    }

    companion object {
        fun fromJson(o: JSONObject): KeystonePosition {
            val v = o.optJSONObject("values")
            val values = mutableMapOf<String, String>()
            if (v != null) {
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    values[k] = v.optString(k, "")
                }
            }
            return KeystonePosition(o.getString("name"), values, o.optLong("savedAt", 0L))
        }

        fun listToJson(list: List<KeystonePosition>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(raw: String?): List<KeystonePosition> {
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

/**
 * Ce que l'app a appris automatiquement sur le projecteur :
 *  - menuComponent : activité du réglage trapèze manuel (package/activité), pour l'ouvrir en un clic ;
 *  - keystoneKeys  : réglages système qui changent quand on modifie la correction trapézoïdale.
 */
data class EngineConfig(
    val menuComponent: String = "",
    val keystoneKeys: Set<String> = emptySet()
) {
    fun toJson(): String {
        val keys = JSONArray()
        keystoneKeys.sorted().forEach { keys.put(it) }
        return JSONObject().put("menuComponent", menuComponent).put("keystoneKeys", keys).toString()
    }

    companion object {
        fun fromJson(raw: String?): EngineConfig {
            if (raw.isNullOrBlank()) return EngineConfig()
            return try {
                val o = JSONObject(raw)
                val array = o.optJSONArray("keystoneKeys")
                val keys = if (array == null) emptySet() else (0 until array.length()).map { array.getString(it) }.toSet()
                EngineConfig(o.optString("menuComponent", ""), keys)
            } catch (e: Exception) {
                EngineConfig()
            }
        }
    }
}
