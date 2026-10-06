package io.github.sharjeelmazhar.phonemic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One screen: switch the mic service on/off, approve pairings, list and forget computers. */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var computers: LinearLayout
    private lateinit var battery: Button
    private lateinit var nameView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var shownPairing: MicService.Pairing? = null
    private var shownComputers: String? = null
    private var plainColor = 0

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, dp(6))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()                                   // the screen has its own title
        store = Store(this)
        MicService.channels(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        col.addView(text("Phone Mic", 26f, true))
        col.addView(text("Use this phone as the microphone of your Linux computer, over Wi-Fi. " +
            "Install phone-mic on the computer; it finds this phone on the same network by itself.", 14f))
        status = text("", 18f, true).apply { setPadding(0, dp(18), 0, dp(8)) }
        col.addView(status)
        plainColor = status.currentTextColor
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        col.addView(toggle)
        nameView = text("", 14f)
        col.addView(nameView)
        col.addView(Button(this).apply { text = "Rename this phone"; setOnClickListener { rename() } })
        battery = Button(this).apply {
            text = "Allow running in the background"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        col.addView(battery)
        col.addView(text("Paired computers", 18f, true).apply { setPadding(0, dp(18), 0, 0) })
        computers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(computers)
        col.addView(text("Tips: on Xiaomi / HyperOS also switch on Autostart for Phone Mic (Settings → Apps → Phone Mic) " +
            "and lock it in the recent apps, otherwise the system may close it. The microphone is only used while a " +
            "paired computer is connected; Android then shows its green mic dot.", 13f).apply { setPadding(0, dp(18), 0, 0) })
        // Android 15 draws apps under the status and navigation bars: keep the content clear of them
        setContentView(ScrollView(this).apply {
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
            }
        })
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        getSystemService(NotificationManager::class.java).cancel(MicService.NOTE_ALERT)
        // Started in the background (after a reboot or by the system), Android gives the service a muted mic.
        // From here the app is in the foreground: starting it again gives it the real microphone.
        if (hasMic() && (MicService.micBlocked || !MicService.running) && (store.enabled || !store.started)) {
            if (MicService.micBlocked) stopService(Intent(this, MicService::class.java))
            MicService.start(this)
        }
        ui.post(tick)
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // first start: switch on as soon as the mic is allowed, nothing else to tap
        if (hasMic() && !MicService.running && !store.started) MicService.start(this)
        refresh()
    }

    private fun onToggle() {
        if (MicService.running) MicService.stop(this)
        else if (!hasMic()) askPermissions()
        else MicService.start(this)
        ui.postDelayed({ refresh() }, 300)
    }

    private fun rename() {
        val edit = EditText(this).apply { setText(store.phoneName); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Name shown on the computer").setView(edit)
            .setPositiveButton("Save") { _, _ -> if (edit.text.isNotBlank()) store.phoneName = edit.text.toString(); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refresh() {
        val to = MicService.streamingTo
        status.text = when {
            !hasMic() -> "Microphone permission needed"
            MicService.micBlocked -> "Android is blocking the mic"
            !MicService.running -> MicService.lastError ?: "Off"
            to != null -> "● Streaming to $to"
            else -> "On, waiting for the computer"
        }
        status.setTextColor(if (to != null) 0xFF2E7D32.toInt() else plainColor)
        toggle.text = if (MicService.running) "Turn off" else "Turn on"
        nameView.text = "This phone appears as “${store.phoneName}”"
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        battery.visibility = if (ignoring) View.GONE else View.VISIBLE

        val list = store.computers()
        val sig = list.joinToString { it.id }
        if (sig != shownComputers) {
            shownComputers = sig
            computers.removeAllViews()
            if (list.isEmpty()) computers.addView(text("None yet. When the computer finds this phone, a code appears here to confirm.", 14f))
            for (c in list) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(text(c.name), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this).apply {
                    text = "Forget"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setMessage("Forget “${c.name}”? It will have to be paired again.")
                            .setPositiveButton("Forget") { _, _ -> store.forget(c.id); refresh() }
                            .setNegativeButton("Cancel", null).show()
                    }
                })
                computers.addView(row)
            }
        }

        val p = MicService.pending
        if (p !== shownPairing) {
            dialog?.dismiss(); dialog = null; shownPairing = p
            if (p != null) dialog = AlertDialog.Builder(this)
                .setTitle("Let “${p.computerName}” use this microphone?")
                .setMessage("Code: ${p.code}\n\nAllow only if the computer shows the same code (Phone Mic tile, notification or `phone-mic status`).")
                .setPositiveButton("Allow") { _, _ -> MicService.answer(this, true) }
                .setNegativeButton("Deny") { _, _ -> MicService.answer(this, false) }
                .setCancelable(false)
                .show()
        }
    }
}
