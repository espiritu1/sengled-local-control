package com.sengled.control

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for bulb hardware (id, default name, IP, MAC).
 *
 * The hardcoded defaults are kept for backward compatibility. Newly paired
 * bulbs are persisted in SharedPreferences and merged at load time.
 */
object BulbRegistry {

    private const val PREFS_NAME = "sengled_bulbs"
    private const val KEY_BULBS = "bulbs_json"
    private const val KEY_REMOVED_IDS = "removed_ids"

    // ── Hardcoded defaults (backward compat) ───────────────────────────

    private val defaultBulbs = listOf(
        Bulb("sala", "Lampara de sala", "192.168.68.150"),
        Bulb("blanca", "Luz blanca", "192.168.68.118")
    )

    // ── Public API ─────────────────────────────────────────────────────

    /** Load all bulbs: hardcoded defaults + any paired bulbs from prefs. */
    fun getBulbs(context: Context): List<Bulb> {
        val paired = loadPaired(context)
        val removedIds = loadRemovedIds(context)
        // Merge: paired bulbs override defaults with same id, extras are appended
        val result = mutableListOf<Bulb>()
        val pairedIds = paired.map { it.id }.toSet()

        for (b in defaultBulbs) {
            if (b.id in removedIds) continue
            val override = paired.firstOrNull { it.id == b.id }
            result.add(override ?: b)
        }
        // Add paired bulbs that don't overlap with defaults
        for (b in paired) {
            if (b.id in removedIds) continue
            if (b.id !in pairedIds || defaultBulbs.none { it.id == b.id }) {
                if (result.none { it.id == b.id }) {
                    result.add(b)
                }
            }
        }
        return result
    }

    /** Save a newly paired bulb. */
    fun addBulb(context: Context, bulb: Bulb, mac: String = "") {
        val paired = loadPaired(context).toMutableList()
        val existing = paired.indexOfFirst { it.id == bulb.id }
        if (existing >= 0) {
            paired[existing] = bulb
        } else {
            paired.add(bulb)
        }
        savePaired(context, paired, mac)
    }

    /** Remove a bulb by id. Removes from paired list and marks defaults as removed. */
    fun removeBulb(context: Context, bulbId: String) {
        val paired = loadPaired(context).toMutableList()
        paired.removeAll { it.id == bulbId }
        savePaired(context, paired)
        // Also mark as removed so hardcoded defaults don't reappear
        addRemovedId(context, bulbId)
    }

    /** Get MAC for a bulb (only available for paired bulbs). */
    fun getMac(context: Context, bulbId: String): String {
        val p = prefs(context)
        return p.getString("mac_$bulbId", "") ?: ""
    }

    // ── Persistence ────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadPaired(context: Context): List<Bulb> {
        val json = prefs(context).getString(KEY_BULBS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<Bulb>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Bulb(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    ip = obj.getString("ip")
                ))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePaired(context: Context, bulbs: List<Bulb>, mac: String = "") {
        val arr = JSONArray()
        for (b in bulbs) {
            arr.put(JSONObject()
                .put("id", b.id)
                .put("name", b.name)
                .put("ip", b.ip)
            )
        }
        val editor = prefs(context).edit()
        editor.putString(KEY_BULBS, arr.toString())
        if (mac.isNotEmpty() && bulbs.isNotEmpty()) {
            editor.putString("mac_${bulbs.last().id}", mac)
        }
        editor.apply()
    }

    private fun loadRemovedIds(context: Context): Set<String> {
        val json = prefs(context).getString(KEY_REMOVED_IDS, null) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun addRemovedId(context: Context, bulbId: String) {
        val ids = loadRemovedIds(context).toMutableSet()
        ids.add(bulbId)
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        prefs(context).edit().putString(KEY_REMOVED_IDS, arr.toString()).apply()
    }
}
