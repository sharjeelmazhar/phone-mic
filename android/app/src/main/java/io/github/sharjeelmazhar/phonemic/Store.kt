package io.github.sharjeelmazhar.phonemic

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Settings on the phone: its id and name, whether the mic service should run, the paired computers. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("phonemic", Context.MODE_PRIVATE)

    /** [lastSeen] = last time it streamed (ms), [hosts] = its addresses, to announce this phone to them directly. */
    data class Computer(val id: String, val name: String, val key: ByteArray, val lastSeen: Long, val hosts: List<String>)

    val phoneId: String
        get() = prefs.getString("phoneId", null) ?: Proto.randomHex(8).also { prefs.edit().putString("phoneId", it).apply() }

    var phoneName: String
        get() = prefs.getString("phoneName", null) ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        set(v) = prefs.edit().putString("phoneName", v.trim().take(40)).apply()

    /** The person switched the mic on (and did not switch it off): it is started again after reboots and kills. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** False until the mic was switched on once: the first start needs no tap. */
    var started: Boolean
        get() = prefs.getBoolean("started", false)
        set(v) = prefs.edit().putBoolean("started", v).apply()

    /** Xiaomi's Autostart switch cannot be read by apps: remember that the person said it is done. */
    var autostartDone: Boolean
        get() = prefs.getBoolean("autostartDone", false)
        set(v) = prefs.edit().putBoolean("autostartDone", v).apply()

    /** The person chose to keep Phone Mic's notifications (the "hide the notification" step is not shown again). */
    var notesKept: Boolean
        get() = prefs.getBoolean("notesKept", false)
        set(v) = prefs.edit().putBoolean("notesKept", v).apply()

    private fun all() = JSONObject(prefs.getString("computers", "{}")!!)
    private fun save(o: JSONObject) = prefs.edit().putString("computers", o.toString()).commit()

    @Synchronized
    fun computers(): List<Computer> {
        val o = all()
        return o.keys().asSequence().map { id ->
            val c = o.getJSONObject(id)
            val hosts = c.optJSONArray("hosts")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            Computer(id, c.getString("name"), Proto.unhex(c.getString("key")), c.optLong("lastSeen"), hosts)
        }.sortedByDescending { it.lastSeen }.toList()
    }

    fun computer(id: String) = computers().firstOrNull { it.id == id }

    @Synchronized
    fun addComputer(id: String, name: String, key: ByteArray, host: String?) {
        val o = all()
        // a computer that was paired again (its config was reset) shows up once, not twice
        for (other in o.keys().asSequence().toList()) if (other != id && o.getJSONObject(other).getString("name") == name &&
            host != null && o.getJSONObject(other).optJSONArray("hosts")?.toString()?.contains("\"$host\"") == true) o.remove(other)
        o.put(id, JSONObject().put("name", name).put("key", Proto.hex(key)).put("lastSeen", System.currentTimeMillis())
            .put("hosts", JSONArray(listOfNotNull(host))))
        save(o)
    }

    @Synchronized
    fun seen(id: String, name: String, host: String) {
        val o = all()
        val c = o.optJSONObject(id) ?: return
        val hosts = (listOf(host) + (c.optJSONArray("hosts")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()))
            .distinct().take(4)
        c.put("name", name).put("lastSeen", System.currentTimeMillis()).put("hosts", JSONArray(hosts))
        save(o)
    }

    /** The stream just ended: "last connected" counts from now, not from when it started. */
    @Synchronized
    fun touch(id: String) { val o = all(); o.optJSONObject(id)?.put("lastSeen", System.currentTimeMillis()) ?: return; save(o) }

    @Synchronized
    fun forget(id: String) { val o = all(); o.remove(id); save(o) }

    // ---- QR codes scanned in the last 10 minutes: computer id -> secret, name, addresses ----

    @Synchronized
    fun addQr(q: Proto.QrPair) {
        val o = JSONObject(prefs.getString("qr", "{}")!!)
        o.put(q.computerId, JSONObject().put("name", q.name).put("secret", q.secret)
            .put("hosts", JSONArray(q.hosts)).put("until", System.currentTimeMillis() + 10 * 60_000))
        prefs.edit().putString("qr", o.toString()).commit()
    }

    @Synchronized
    fun qr(computerId: String): Proto.QrPair? {
        val c = JSONObject(prefs.getString("qr", "{}")!!).optJSONObject(computerId) ?: return null
        if (c.getLong("until") < System.currentTimeMillis()) return null
        val h = c.getJSONArray("hosts")
        return Proto.QrPair(computerId, c.getString("name"), c.getString("secret"), List(h.length()) { h.getString(it) })
    }

    @Synchronized
    fun qrHosts(): List<String> {
        val o = JSONObject(prefs.getString("qr", "{}")!!)
        val now = System.currentTimeMillis()
        return o.keys().asSequence().map { o.getJSONObject(it) }.filter { it.getLong("until") > now }
            .flatMap { c -> c.getJSONArray("hosts").let { a -> List(a.length()) { a.getString(it) } } }.toList()
    }

    /** Names of the computers whose QR code was scanned and that have not paired yet. */
    @Synchronized
    fun qrNames(): List<String> {
        val o = JSONObject(prefs.getString("qr", "{}")!!)
        val now = System.currentTimeMillis()
        return o.keys().asSequence().map { o.getJSONObject(it) }.filter { it.getLong("until") > now }.map { it.getString("name") }.toList()
    }

    @Synchronized
    fun dropQr(computerId: String) {
        val o = JSONObject(prefs.getString("qr", "{}")!!); o.remove(computerId)
        prefs.edit().putString("qr", o.toString()).commit()
    }
}
