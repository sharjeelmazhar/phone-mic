package io.github.sharjeelmazhar.phonemic

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Invisible, closes at once. Opened from the background (after a reboot, or when the system killed the app) so that the
 * mic service is started while the app counts as "in the foreground": only then does Android give it the microphone.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && Store(this).enabled) {
            runCatching { MicService.start(this) }.onFailure { MicService.askForTap(this) }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
