package com.sengled.control

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for bulb hardware (id, name, IP, MAC).
 *
 * Bulbs live only in SharedPreferences — there are no hardcoded defaults.
 * Newly paired bulbs are persisted and loaded at runtime.
 */
object BulbRegistry {

    private const val PREFS_NAME = "sengled_bulbs"
    private const val KEY_BULBS = "bulbs_json"

    // ── Public API ─────────────────────────────────────────────────────

    /** Load all bulbs from prefs. */
    fun getBulbs(context: Context): List<Bulb> = loadPaired(context)

    /** Save a newly paired bulb. */
    fun addBulb(context: Context, bulb: Bulb, mac: String = "") {
        val paired = loadPaired(context).toMutableList()
        val existing = paired.indexOfFirst { it.id == bulb.id }
        if (existing >= 0) {
            paired[existing] = bulb
        } else {
            paired.add(bulb)
        }
        savePaired(context, paired, bulb.id, mac)
    }

    /** Update the IP of an existing bulb (paired only). */
    fun updateBulbIp(context: Context, bulbId: String, newIp: String) {
        val paired = loadPaired(context).toMutableList()
        val idx = paired.indexOfFirst { it.id == bulbId }
        if (idx < 0) return
        val b = paired[idx]
        paired[idx] = b.copy(ip = newIp)
        savePaired(context, paired)
    }

    /** Remove a bulb by id. */
    fun removeBulb(context: Context, bulbId: String) {
        val paired = loadPaired(context).toMutableList()
        paired.removeAll { it.id == bulbId }
        savePaired(context, paired)
    }

    /**
     * Reorder bulbs by id. Callers pass the full desired order; any id not
     * present is dropped, any bulb not listed stays at the end (defensive).
     */
    fun reorderBulbs(context: Context, orderedIds: List<String>) {
        val paired = loadPaired(context).toMutableList()
        val byId = paired.associateBy { it.id }
        val reordered = mutableListOf<Bulb>()
        for (id in orderedIds) {
            byId[id]?.let { reordered.add(it) }
        }
        for (b in paired) {
            if (!orderedIds.contains(b.id)) reordered.add(b)
        }
        savePaired(context, reordered)
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

    private fun savePaired(context: Context, bulbs: List<Bulb>, macForId: String? = null, mac: String = "") {
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
        if (mac.isNotEmpty() && macForId != null) {
            editor.putString("mac_$macForId", mac)
        }
        editor.apply()
    }
}
