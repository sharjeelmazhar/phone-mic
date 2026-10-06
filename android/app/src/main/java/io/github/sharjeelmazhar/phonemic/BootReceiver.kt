package io.github.sharjeelmazhar.phonemic

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a phone reboot or an app update. Android does not give an app started in the background the microphone,
 * so instead of starting muted we ask for one tap: the notification opens the app, which starts the service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Store(context).enabled || MicService.running) return
        MicService.channels(context)
        val n = Notification.Builder(context, "alerts")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Tap to turn Phone Mic back on")
            .setContentText("Android needs the app opened once after a restart before it may use the microphone.")
            .setContentIntent(MicService.openApp(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(MicService.NOTE_ALERT, n)
    }
}
