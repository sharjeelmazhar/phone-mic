package io.github.sharjeelmazhar.phonemic

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** "Phone Mic" in the phone's Quick Settings: on / off without opening the app; the subtitle shows the state. */
class MicTile : TileService() {
    companion object {
        /** Redraw the tile (when the service starts, stops, or starts or stops streaming). */
        fun refresh(ctx: Context) = runCatching { requestListeningState(ctx, ComponentName(ctx, MicTile::class.java)) }
    }

    override fun onStartListening() = draw()

    override fun onClick() {
        if (MicService.running) { MicService.stop(this); draw(); return }
        // Android gives the microphone only to a service started from the foreground: start it from an (invisible) activity;
        // without the mic permission yet, open the app so it can ask
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasMic) Store(this).enabled = true
        val intent = Intent(this, if (hasMic) LaunchActivity::class.java else MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        open(intent)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }

    private fun draw() {
        val t = qsTile ?: return
        t.icon = Icon.createWithResource(this, R.drawable.ic_mic)
        t.label = "Phone Mic"
        t.state = if (MicService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = when {
            !MicService.running -> "Off"
            MicService.streamingTo != null -> "Streaming"
            else -> "Waiting"
        }
        t.updateTile()
    }
}
