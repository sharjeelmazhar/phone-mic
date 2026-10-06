package io.github.sharjeelmazhar.phonemic

import android.content.Context
import android.os.Build
import org.json.JSONObject

/** Settings on the phone: its id and name, whether the mic service should run, and the paired computers. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("phonemic", Context.MODE_PRIVATE)

    data class Computer(val id: String, val name: String, val key: ByteArray)

    val phoneId: String
        get() = prefs.getString("phoneId", null) ?: Proto.randomHex(8).also { prefs.edit().putString("phoneId", it).apply() }

    var phoneName: String
        get() = prefs.getString("phoneName", null) ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        set(v) = prefs.edit().putString("phoneName", v.trim().take(40)).apply()

    /** The person switched the mic on (and did not switch it off): after a reboot we offer to start again. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** False until the mic was switched on once: the first start needs no tap. */
    var started: Boolean
        get() = prefs.getBoolean("started", false)
        set(v) = prefs.edit().putBoolean("started", v).apply()

    @Synchronized
    fun computers(): List<Computer> {
        val o = JSONObject(prefs.getString("computers", "{}")!!)
        return o.keys().asSequence().map { id ->
            val c = o.getJSONObject(id)
            Computer(id, c.getString("name"), Proto.unhex(c.getString("key")))
        }.sortedBy { it.name.lowercase() }.toList()
    }

    fun computer(id: String) = computers().firstOrNull { it.id == id }

    @Synchronized
    fun addComputer(id: String, name: String, key: ByteArray) {
        val o = JSONObject(prefs.getString("computers", "{}")!!)
        o.put(id, JSONObject().put("name", name).put("key", Proto.hex(key)))
        prefs.edit().putString("computers", o.toString()).commit()
    }

    @Synchronized
    fun forget(id: String) {
        val o = JSONObject(prefs.getString("computers", "{}")!!)
        o.remove(id)
        prefs.edit().putString("computers", o.toString()).commit()
    }
}
