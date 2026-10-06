package io.github.sharjeelmazhar.phonemic

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay

/** One screen: status and why, the on/off switch, setup steps still missing, QR pairing, paired computers. */
class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private val message = mutableStateOf<String?>(null)
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // first start: switch on as soon as the mic is allowed, nothing else to tap
        if (hasMic() && !MicService.running && !store.started) MicService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()                       // status bar icons dark on a light background, light on dark
        super.onCreate(savedInstanceState)
        store = Store(this)
        MicService.channels(this)
        handleLink(intent)
        setContent { PhoneMicTheme { Screen() } }
        // Only the microphone. On Android 13+ the notification permission is left alone on purpose: without it the
        // always-running service stays out of the notification shade and the lock screen, and Android's own green
        // microphone dot shows when the computer listens.
        if (!hasMic()) askPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleLink(intent) }

    override fun onResume() {
        super.onResume()
        getSystemService(android.app.NotificationManager::class.java).cancel(MicService.NOTE_ALERT)
        // From here the app is in the foreground: (re)starting the service gives it the real microphone.
        if (hasMic() && (store.enabled || !store.started) && (MicService.micBlocked || !MicService.running)) {
            if (MicService.micBlocked) stopService(Intent(this, MicService::class.java))
            MicService.start(this)
        }
    }

    fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun handleLink(intent: Intent?) {
        val text = intent?.dataString ?: return
        onQr(text)
        intent.data = null
    }

    private fun onQr(text: String?) {
        val q = Proto.parseQr(text)
        if (q == null) {
            message.value = if (text?.contains("PhoneMic.apk") == true) "That code is the app download link. Scan the code that  phone-mic pair  shows."
                else "That is not a Phone Mic pairing code. On the computer run: phone-mic pair"
            return
        }
        store.addQr(q)
        if (hasMic() && !MicService.running) MicService.start(this)
        MicService.announceNow(this)
        message.value = "Pairing with ${q.name}… a few seconds."
    }

    private fun scan() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { onQr(it.rawValue) }
            .addOnFailureListener { message.value = "Scanner not available on this phone. Scan the code with the camera app instead." }
    }

    private fun open(intent: Intent, fallback: Intent? = null) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { fallback?.let { runCatching { startActivity(it) } } }
    }

    /** Xiaomi's Autostart list (Security app), else its permission page, else the app's settings. */
    private fun openXiaomi(autostart: Boolean) {
        val pkg = Uri.parse("package:$packageName")
        val tries = buildList {
            if (autostart) add(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            add(Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", packageName))
            add(Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
        for (i in tries) if (runCatching { startActivity(i) }.isSuccess) return
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true }
    }

    /** Xiaomi's own permissions (10008 Autostart, 10021 open windows from the background): true / false, or null where
     *  they cannot be read (other brands, or Android hides the method). */
    private fun miuiAllowed(op: Int): Boolean? = runCatching {
        val aom = getSystemService(android.app.AppOpsManager::class.java)
        val m = android.app.AppOpsManager::class.java.getMethod("checkOpNoThrow",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        (m.invoke(aom, op, applicationInfo.uid, packageName) as Int) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    private fun ago(t: Long) = if (t == 0L) "never connected" else
        "last connected " + DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

    // ---- UI ---------------------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }        // the service's state is read once a second
        val snack = remember { SnackbarHostState() }
        val msg = message.value
        LaunchedEffect(msg) { if (msg != null) { snack.showSnackbar(msg); message.value = null } }
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = { LargeTopAppBar(title = { Text("Phone Mic") }, scrollBehavior = scroll) },
            snackbarHost = { SnackbarHost(snack) },
        ) { pad ->
            LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(),
                        bottom = pad.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { StatusCard(tick) }
                    item { SetupCard(tick) }
                    item { PairCard() }
                    item { ComputersCard(tick) }
                    item { PhoneCard() }
                }
        }
        if (tick >= 0) {
            MicService.pending?.let { p ->
                AlertDialog(
                    onDismissRequest = {},
                    icon = { Icon(Icons.Outlined.Computer, null) },
                    title = { Text("Let “${p.computerName}” use this microphone?") },
                    text = {
                        Column {
                            Text("Allow only if the computer shows the same code:")
                            Spacer(Modifier.height(12.dp))
                            Text(p.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    confirmButton = { Button(onClick = { MicService.answer(this, true) }) { Text("Allow") } },
                    dismissButton = { TextButton(onClick = { MicService.answer(this, false) }) { Text("Deny") } },
                )
            }
        }
    }

    private class Status(val icon: ImageVector, val title: String, val body: String, val tone: Int)   // tone: 0 calm, 1 good, 2 problem

    private fun status(): Status {
        val to = MicService.streamingTo
        val computers = store.computers()
        return when {
            !hasMic() -> Status(Icons.Outlined.MicOff, "Microphone access needed", "Phone Mic can only stream after you allow the microphone.", 2)
            MicService.micBlocked -> Status(Icons.Outlined.MicOff, "Android paused the microphone", "Restarting… if it stays like this, open the app once.", 2)
            !MicService.running -> Status(Icons.Outlined.PowerSettingsNew, "Phone Mic is off",
                MicService.lastError ?: "Turn it on and your computer finds this phone by itself.", 0)
            to != null -> Status(Icons.Outlined.GraphicEq, "Streaming to $to", "Your computer is using this phone's microphone.", 1)
            !onWifi() -> Status(Icons.Outlined.WifiOff, "Not on Wi-Fi", "Phone Mic connects over your home network. Join the same network as the computer.", 2)
            store.qrNames().isNotEmpty() && MicService.unreachable.isNotEmpty() && MicService.reachable.isEmpty() ->
                Status(Icons.Outlined.WifiOff, "Can't reach ${store.qrNames().first()}",
                    "This phone gets no answer from the computer (${MicService.unreachable.joinToString()}). Your router keeps this " +
                        "Wi-Fi apart from it: switch the phone to your other Wi-Fi network (or turn off “AP isolation” in the router).", 2)
            store.qrNames().isNotEmpty() -> Status(Icons.Outlined.Sync, "Pairing with ${store.qrNames().first()}…",
                "Keep  phone-mic pair  running on the computer. Takes a few seconds.", 0)
            computers.isEmpty() -> Status(Icons.Outlined.QrCode2, "Not paired yet",
                "On the computer run  phone-mic pair  and scan the QR code. The first computer can also pair by itself: just wait a moment.", 0)
            MicService.unreachable.isNotEmpty() && MicService.reachable.isEmpty() -> Status(Icons.Outlined.WifiOff,
                "Can't reach ${computers.first().name}",
                "This phone gets no answer from ${MicService.unreachable.joinToString()}. Either the computer is off, or your router " +
                    "keeps this Wi-Fi apart from it: switch the phone to your other Wi-Fi network (or turn off “AP isolation” in the router).", 2)
            MicService.reachable.isNotEmpty() && System.currentTimeMillis() - MicService.waitingSince > 20_000 -> Status(
                Icons.Outlined.ErrorOutline, "${computers.first().name} is on, but not connecting",
                "The computer answers, but its Phone Mic does not connect. Switch on the Phone Mic tile in its Quick Settings " +
                    "(or run  phone-mic on).", 2)
            MicService.lastEvent != null -> Status(Icons.Outlined.ErrorOutline, "Waiting for your computer", MicService.lastEvent!!, 2)
            else -> Status(Icons.Outlined.Sync, "Waiting for your computer",
                "Connects by itself when ${computers.joinToString(" or ") { it.name }} is on and on the same network. " +
                    "${computers.first().name}: ${ago(computers.first().lastSeen)}." +
                    // waiting long while on Wi-Fi: the usual reason is the router keeping its networks apart
                    if (System.currentTimeMillis() - MicService.waitingSince > 60_000)
                        "\n\nComputer on but still nothing? Some routers keep one of their Wi-Fi networks (often the 2.4 GHz one) " +
                            "apart from the computer: switch this phone to the other Wi-Fi, or turn off “AP isolation” in the router."
                    else "", 0)
        }
    }

    @Composable
    private fun StatusCard(tick: Int) {
        // tick must be read: Compose skips a composable whose parameters are unused, and the card would never refresh
        val s = remember(tick) { status() }
        val cs = MaterialTheme.colorScheme
        val (bg, fg) = when (s.tone) {
            1 -> cs.primaryContainer to cs.onPrimaryContainer
            2 -> cs.errorContainer to cs.onErrorContainer
            else -> cs.surfaceContainerHigh to cs.onSurface
        }
        Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
            Column(Modifier.padding(20.dp).fillMaxWidth()) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(if (s.tone == 1) cs.primary else fg.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    Icon(s.icon, null, tint = if (s.tone == 1) cs.onPrimary else fg, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(s.body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                when {
                    !hasMic() -> Button(onClick = { askPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }) { Text("Allow microphone") }
                    MicService.running -> OutlinedButton(onClick = { MicService.stop(this@MainActivity) }) {
                        Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Turn off")
                    }
                    else -> Button(onClick = { MicService.start(this@MainActivity) }) {
                        Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Turn on")
                    }
                }
            }
        }
    }

    private class Step(val icon: ImageVector, val title: String, val body: String, val action: () -> Unit,
                       val done: (() -> Unit)? = null, val button: String = "Allow", val doneText: String = "Done")

    @Composable
    private fun SetupCard(tick: Int) {
        val pkg = Uri.parse("package:$packageName")
        val xiaomi = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
        val steps = remember(tick) { buildList {
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                add(Step(Icons.Outlined.BatteryChargingFull, "Run in the background", "So Android does not stop Phone Mic to save battery.",
                    { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) }))
            if (!Settings.canDrawOverlays(this@MainActivity))
                add(Step(Icons.Outlined.RestartAlt, "Start by itself after a restart",
                    "Allow “Display over other apps”. Android only gives the microphone to an app that was opened, so after a reboot Phone Mic opens itself for a split second (invisible).",
                    { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) }))
            val auto = if (xiaomi) miuiAllowed(10008) else null
            val popups = if (xiaomi) miuiAllowed(10021) else null
            val known = auto != null && popups != null
            if (xiaomi && (if (known) !(auto!! && popups!!) else !store.autostartDone))
                add(Step(Icons.Outlined.PhonelinkSetup, "Xiaomi: Autostart and pop-ups",
                    buildString {
                        append("Without these, HyperOS / MIUI never restarts Phone Mic after a reboot. Switch on ")
                        append(listOfNotNull(if (auto != true) "Autostart" else null,
                            if (popups != true) "“Display pop-up windows while running in the background”" else null).joinToString(" and "))
                        append(", and set Battery saver to No restrictions.")
                        if (!known) append(" Then tap Done.")
                    },
                    { openXiaomi(auto != true) },
                    if (known) null else { { store.autostartDone = true } }, "Open settings"))
            if (Build.VERSION.SDK_INT >= 33 && getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() && !store.notesKept)
                add(Step(Icons.Outlined.NotificationsOff, "Hide the notification (recommended)",
                    "Android already shows its green microphone dot while the computer listens. Turn off Notifications for " +
                        "Phone Mic and it stays out of your notification shade and lock screen; it keeps working the same.",
                    { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) },
                    { store.notesKept = true }, "Open", "Keep it"))
        } }
        if (steps.isEmpty()) return
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text("Finish setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                for (st in steps) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(st.icon, null) },
                        headlineContent = { Text(st.title, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(st.body) },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                        st.done?.let { TextButton(onClick = it) { Text(st.doneText) }; Spacer(Modifier.width(8.dp)) }
                        FilledTonalButton(onClick = st.action) { Text(st.button) }
                    }
                }
            }
        }
    }

    @Composable
    private fun PairCard() {
        ElevatedCard {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pair a computer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("On the computer run  phone-mic pair  and scan the code it shows.", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(onClick = { scan() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.QrCodeScanner, "Scan QR code") }
            }
        }
    }

    @Composable
    private fun ComputersCard(tick: Int) {
        val list = remember(tick) { store.computers() }
        if (list.isEmpty()) return
        var forget by remember { mutableStateOf<Store.Computer?>(null) }
        ElevatedCard {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text("Paired computers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                for (c in list) {
                    val live = MicService.streamingTo == c.name
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Outlined.Computer, null,
                            tint = if (live) MaterialTheme.colorScheme.primary else LocalContentColor.current) },
                        headlineContent = { Text(c.name) },
                        supportingContent = { Text((if (live) "connected now" else ago(c.lastSeen)) + (c.hosts.firstOrNull()?.let { " · $it" } ?: "")) },
                        trailingContent = { IconButton(onClick = { forget = c }) { Icon(Icons.Outlined.DeleteOutline, "Forget ${c.name}") } },
                    )
                }
            }
        }
        forget?.let { c ->
            AlertDialog(onDismissRequest = { forget = null },
                title = { Text("Forget “${c.name}”?") },
                text = { Text("It can no longer use this microphone until it is paired again.") },
                confirmButton = { TextButton(onClick = { store.forget(c.id); forget = null }) { Text("Forget") } },
                dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } })
        }
    }

    @Composable
    private fun PhoneCard() {
        var editing by remember { mutableStateOf(false) }
        ElevatedCard {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.Smartphone, null) },
                overlineContent = { Text("This phone appears as") },
                headlineContent = { Text(store.phoneName) },
                trailingContent = { IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, "Rename") } },
            )
        }
        if (editing) {
            var text by remember { mutableStateOf(store.phoneName) }
            AlertDialog(onDismissRequest = { editing = false },
                title = { Text("Name shown on the computer") },
                text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true) },
                confirmButton = { TextButton(onClick = { if (text.isNotBlank()) store.phoneName = text; editing = false }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
        }
    }
}

/** Material 3 with the wallpaper colours of Android 12+ (Pixel, Samsung One UI, Xiaomi HyperOS...), own colours before. */
@Composable
fun PhoneMicTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFB4C5FF), primaryContainer = Color(0xFF2E3A8C), tertiaryContainer = Color(0xFF004D61))
        else -> lightColorScheme(primary = Color(0xFF4F46E5), primaryContainer = Color(0xFFE0E0FF), tertiaryContainer = Color(0xFFD5F2FF))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
