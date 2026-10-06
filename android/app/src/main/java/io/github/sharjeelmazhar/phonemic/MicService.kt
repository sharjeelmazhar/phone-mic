package io.github.sharjeelmazhar.phonemic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Runs while Phone Mic is switched on. Listens for the computer, announces the phone on the LAN, and records the
 * microphone only while a paired computer is connected. See [Proto] for the protocol.
 */
class MicService : Service() {
    companion object {
        private const val TAG = "PhoneMic"
        const val ACTION_STOP = "stop"
        const val ACTION_ALLOW = "allow"
        const val ACTION_DENY = "deny"
        private const val NOTE_STATUS = 1
        private const val NOTE_PAIR = 2
        const val NOTE_ALERT = 3

        @Volatile var running = false; private set
        @Volatile var streamingTo: String? = null; private set
        @Volatile var pending: Pairing? = null; private set
        @Volatile var micBlocked = false; private set
        @Volatile var lastError: String? = null; private set

        fun start(ctx: Context) {
            Store(ctx).apply { enabled = true; started = true }
            ctx.startForegroundService(Intent(ctx, MicService::class.java))
        }
        fun stop(ctx: Context) {
            Store(ctx).enabled = false
            ctx.stopService(Intent(ctx, MicService::class.java))
        }
        fun answer(ctx: Context, allow: Boolean) =
            ctx.startService(Intent(ctx, MicService::class.java).setAction(if (allow) ACTION_ALLOW else ACTION_DENY))

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("status", "Microphone status", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("alerts", "Pairing and problems", NotificationManager.IMPORTANCE_HIGH))
        }
        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    class Pairing(val computerName: String, val code: String) {
        val latch = CountDownLatch(1)
        @Volatile var allowed = false
        fun answer(allow: Boolean) { allowed = allow; latch.countDown() }
    }

    private lateinit var store: Store
    @Volatile private var stopping = false
    private var server: ServerSocket? = null
    private var current: Socket? = null                    // the socket that is streaming right now
    private val micLock = ReentrantLock()                  // one AudioRecord at a time
    private val denied = HashMap<String, Long>()           // computer id -> when the person said no
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val version by lazy { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        channels(this)
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE_STATUS, statusNote(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTE_STATUS, statusNote())
        } catch (e: Exception) {
            // Android does not let the microphone service start from the background (after a reboot, for example)
            Log.w(TAG, "cannot start in the foreground: $e")
            lastError = "Android did not allow starting from the background: open Phone Mic once."
            stopSelf(); return
        }
        running = true; stopping = false; micBlocked = false; lastError = null
        // keep Wi-Fi awake with the screen off, so the computer can reach the phone at any time
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneMic:wifi").apply { setReferenceCounted(false); acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneMic:stream").apply { setReferenceCounted(false) }
        thread(name = "server", isDaemon = true) { serve() }
        thread(name = "announce", isDaemon = true) { announce() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { store.enabled = false; stopSelf() }
            ACTION_ALLOW -> pending?.answer(true)
            ACTION_DENY -> pending?.answer(false)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true; running = false
        pending?.answer(false)
        runCatching { server?.close() }
        synchronized(this) { runCatching { current?.close() } }
        wifiLock?.release(); wakeLock?.release()
        streamingTo = null
        super.onDestroy()
    }

    // ---- network --------------------------------------------------------------------------------------------

    private fun serve() {
        while (!stopping) {
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Proto.TCP_PORT)) }
                server = ss
                while (!stopping) {
                    val s = ss.accept()
                    thread(name = "conn", isDaemon = true) {
                        try { handle(s) } catch (e: Exception) { Log.i(TAG, "connection ended: ${e.message}") }
                        finally { runCatching { s.close() } }
                    }
                }
            } catch (e: Exception) {
                if (stopping) return
                Log.w(TAG, "server: $e"); lastError = "Cannot listen on port ${Proto.TCP_PORT}: ${e.message}"
                Thread.sleep(5000)
            }
        }
    }

    /** Tell the LAN where we are. The computer also finds us without this (last address, subnet scan). */
    private fun announce() {
        val sock = DatagramSocket().apply { broadcast = true }
        while (!stopping) {
            if (streamingTo == null) {
                val msg = "PHONEMIC ${Proto.VERSION} ${store.phoneId} ${Proto.TCP_PORT} ${Proto.encodeName(store.phoneName)}".toByteArray()
                runCatching {
                    for (nif in NetworkInterface.getNetworkInterfaces()) {
                        if (!nif.isUp || nif.isLoopback) continue
                        for (a in nif.interfaceAddresses) {
                            val b = a.broadcast ?: continue
                            runCatching { sock.send(DatagramPacket(msg, msg.size, b, Proto.UDP_PORT)) }
                        }
                    }
                }
            }
            Thread.sleep(3000)
        }
        sock.close()
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (sb.length >= 2048) throw IllegalStateException("line too long")
            sb.append(c.toChar())
        }
    }
    private fun send(out: OutputStream, line: String) { out.write("$line\n".toByteArray()); out.flush() }

    private fun handle(s: Socket) {
        s.soTimeout = 10_000; s.tcpNoDelay = true
        val inp = s.getInputStream(); val out = s.getOutputStream()
        send(out, "PHONEMIC ${Proto.VERSION} ${store.phoneId} $version ${Proto.encodeName(store.phoneName)}")
        val (cid, cname, nonceC, source) = Proto.parse(readLine(inp), "HELLO", 4)
        require(cid.length in 8..64 && nonceC.length == 32) { "bad HELLO" }
        Proto.unhex(nonceC)
        val name = Proto.decodeName(cname)
        val known = store.computer(cid)
        if (known == null) { pair(s, inp, out, cid, name, nonceC); return }
        val nonceP = Proto.randomHex()
        send(out, "AUTH $nonceP")
        val (proof) = Proto.parse(readLine(inp), "PROOF", 1)
        if (!Proto.constantTimeEquals(proof, Proto.proof(known.key, "C", nonceP, nonceC))) {
            send(out, "DENIED"); Log.w(TAG, "wrong proof from ${known.name}"); return
        }
        send(out, "OK ${Proto.RATE} 1 ${Proto.proof(known.key, "P", nonceP, nonceC)}")
        stream(s, inp, out, known.name, Proto.Cipher(Proto.sessionKey(known.key, nonceP, nonceC)), source)
    }

    private fun pair(s: Socket, inp: InputStream, out: OutputStream, cid: String, name: String, nonceC: String) {
        val recent = synchronized(denied) { denied[cid]?.let { System.currentTimeMillis() - it < 10 * 60_000 } ?: false }
        if (recent || pending != null) { send(out, "DENIED"); return }
        val nonceP = Proto.randomHex()
        val dh = Proto.Dh()
        send(out, "PAIR $nonceP ${dh.public}")
        val (pub) = Proto.parse(readLine(inp), "PAIRKEY", 1)
        val key = Proto.pairKey(dh.shared(pub), nonceP, nonceC)
        val p = Pairing(name, Proto.pairCode(key))
        synchronized(this) { if (pending != null) { send(out, "DENIED"); return }; pending = p }
        try {
            notifyPairing(p)
            val answered = p.latch.await(120, TimeUnit.SECONDS)
            if (answered && p.allowed) {
                store.addComputer(cid, name, key)
                send(out, "PAIRED"); Log.i(TAG, "paired with $name")
            } else {
                if (answered) synchronized(denied) { denied[cid] = System.currentTimeMillis() }
                send(out, "DENIED")
            }
        } finally {
            pending = null
            getSystemService(NotificationManager::class.java).cancel(NOTE_PAIR)
        }
    }

    private fun audioSource(name: String) = when (name) {
        "voice-communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        "voice-recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        "unprocessed" -> MediaRecorder.AudioSource.UNPROCESSED
        "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
        else -> MediaRecorder.AudioSource.MIC
    }

    private fun stream(s: Socket, inp: InputStream, out: OutputStream, name: String, cipher: Proto.Cipher, source: String) {
        // a newer connection from the computer replaces the old one (it reconnected after a network change)
        synchronized(this) { runCatching { current?.close() }; current = s }
        micLock.withLock {
            if (s.isClosed) return
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                lastError = "Microphone permission is missing: open Phone Mic."; return
            }
            val min = AudioRecord.getMinBufferSize(Proto.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(audioSource(source), Proto.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 19200))
            } catch (e: Exception) { lastError = "Microphone: ${e.message}"; return }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); lastError = "The microphone is busy or unavailable."; return }
            // keepalive from the computer: a newline every second; nothing for 6 s = it is gone
            s.soTimeout = 6000
            thread(name = "keepalive", isDaemon = true) {
                try { while (inp.read() >= 0) { } } catch (_: Exception) { }
                runCatching { s.close() }
            }
            streamingTo = name; lastError = null; updateStatus(); wakeLock?.acquire(12 * 3600_000L)
            Log.i(TAG, "streaming to $name")
            val buf = ByteArray(1920)                       // 20 ms
            var silentMs = 0
            try {
                rec.startRecording()
                while (!s.isClosed && !stopping) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) { lastError = "Microphone read failed ($n)"; break }
                    // Android hands out exact zeros when it does not let this app use the mic (started in the background)
                    if (buf.all { it.toInt() == 0 }) silentMs += n / 96 else { silentMs = 0; if (micBlocked) { micBlocked = false; updateStatus() } }
                    if (silentMs > 4000 && !micBlocked) { micBlocked = true; notifyBlocked(); updateStatus() }
                    cipher.apply(buf, 0, n)
                    out.write(buf, 0, n)
                }
            } catch (e: Exception) {
                Log.i(TAG, "stream to $name ended: ${e.message}")
            } finally {
                runCatching { rec.stop() }; rec.release()
                synchronized(this) { if (current === s) { current = null; streamingTo = null } }
                if (streamingTo == null) wakeLock?.release()
                updateStatus()
            }
        }
    }

    // ---- notifications ------------------------------------------------------------------------------------

    private fun statusText() = when {
        micBlocked -> "Android is blocking the mic: tap to open Phone Mic"
        streamingTo != null -> "Streaming to $streamingTo"
        else -> "On, waiting for the computer"
    }

    private fun statusNote(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "status")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Phone Mic")
            .setContentText(statusText())
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    private fun updateStatus() {
        if (running) getSystemService(NotificationManager::class.java).notify(NOTE_STATUS, statusNote())
    }

    private fun notifyPairing(p: Pairing) {
        fun act(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, MicService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "alerts")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Let “${p.computerName}” use this microphone?")
            .setContentText("Code ${p.code}: allow only if the computer shows the same code.")
            .setStyle(Notification.BigTextStyle().bigText("Code ${p.code}\nAllow only if the computer shows the same code (Phone Mic tile or `phone-mic status`)."))
            .setContentIntent(openApp(this))
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, "Allow", act(ACTION_ALLOW, 2)).build())
            .addAction(Notification.Action.Builder(null, "Deny", act(ACTION_DENY, 3)).build())
            .setTimeoutAfter(120_000)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTE_PAIR, n)
    }

    private fun notifyBlocked() {
        val n = Notification.Builder(this, "alerts")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Phone Mic needs a tap")
            .setContentText("Android is blocking the microphone because the app was started in the background. Tap to fix.")
            .setContentIntent(openApp(this))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTE_ALERT, n)
    }
}
