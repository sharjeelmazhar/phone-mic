package io.github.sharjeelmazhar.phonemic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Phone restarted, app updated, or the 15-minute watchdog: start the mic service again if it should be running. */
class BootReceiver : BroadcastReceiver() {
    companion object { const val WATCHDOG = "io.github.sharjeelmazhar.phonemic.WATCHDOG" }

    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        if (!store.enabled) return
        MicService.scheduleWatchdog(context)
        if (MicService.running) return
        MicService.restartFromBackground(context, intent.action ?: "?")
        // some phones (Xiaomi: "Display pop-up windows while running in the background") silently refuse the start
        val done = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!MicService.running && store.enabled) MicService.askForTap(context)
            done.finish()
        }, 8000)
    }
}
