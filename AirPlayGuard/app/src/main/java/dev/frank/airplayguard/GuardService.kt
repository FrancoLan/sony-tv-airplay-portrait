package dev.frank.airplayguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.frank.airplayguard.detect.ReceiverProbe
import dev.frank.airplayguard.detect.TrafficMonitor
import dev.frank.airplayguard.power.CountdownOverlay
import dev.frank.airplayguard.power.DisplayController
import dev.frank.airplayguard.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a sideways-mounted TV discoverable while the panel is off, lights it up when a
 * mirroring session starts, and puts it back to sleep after an idle period.
 *
 * Two independent triggers, so it works whether you use the patched receiver or the
 * TV's built-in AirPlay:
 *
 *  - An explicit session broadcast from the patched receiver app. Exact, instant.
 *  - Device-wide download rate. Crude, but a mirroring session is two orders of
 *    magnitude above a standby TV's background chatter, and it needs no permissions
 *    and no cooperation from whatever is receiving.
 */
class GuardService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var locks: NetworkLocks
    private lateinit var display: DisplayController
    private lateinit var probe: ReceiverProbe
    private lateinit var countdown: CountdownOverlay
    private val traffic = TrafficMonitor()

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** elapsedRealtime of the last thing that counts as "activity". */
    @Volatile private var lastActiveAt = SystemClock.elapsedRealtime()

    /** True while the sender is connected but sending no video (screen locked). */
    @Volatile private var videoStalled = false

    /** True for the lifetime of an exact receiver-reported mirroring session. */
    @Volatile private var sessionActive = false

    /**
     * Residual packets after sleeping must not immediately wake the panel again. This is
     * deliberately a short window, not a permanent traffic-wake ban: some senders (for
     * example Douyin live) need traffic to bring the receiver forward before the exact
     * mirroring callback is available.
     */
    @Volatile private var suppressTrafficWakeUntil = 0L

    /**
     * Set once the patched receiver has reported a session. From then on the traffic
     * heuristic is no longer allowed to wake the panel: we have exact information, and
     * guessing on top of it is how a locked phone's audio stream switched the TV back on.
     * Traffic stays as the wake path only for receivers that never broadcast (the TV's
     * built-in AirPlay).
     */
    @Volatile private var sawSessionBroadcast = false

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = intent.getBooleanExtra("active", false)
            val stalled = intent.getBooleanExtra("stalled", false)
            Log.i(TAG, "receiver reports session active=$active stalled=$stalled")
            if (!sawSessionBroadcast) {
                sawSessionBroadcast = true
                Status.wakeSource = "会话广播（精确）"
            }
            markActive()
            sessionActive = active
            videoStalled = active && stalled
            when {
                // Sender locked its screen: video stops, audio keeps playing. Cover the
                // frozen frame and count down; audio survives the panel going off.
                videoStalled -> beginCountdown("iPhone 已锁屏")
                active -> {
                    countdown.cancel()
                    wakeNow("session broadcast")
                }
                else -> beginCountdown("投屏已结束")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        locks = NetworkLocks(this)
        display = DisplayController(this)
        probe = ReceiverProbe(this)
        countdown = CountdownOverlay(this) { prefs.overlayRotation }

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在守护…"))

        locks.acquire()
        display.disableSystemSleepTimeout()
        registerSessionReceiver()
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SLEEP_NOW -> {
                Status.lastSleepMethod = display.sleep().name
                markActive()
            }
            ACTION_WAKE_NOW -> wakeNow("manual")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        countdown.cancel()
        job?.cancel()
        scope.cancel()
        runCatching { unregisterReceiver(sessionReceiver) }
        locks.release()
        display.hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- loop

    private fun startLoop() {
        job?.cancel()
        traffic.reset()
        job = scope.launch {
            var tick = 0L
            while (isActive) {
                delay(TICK_MS)
                if (!prefs.enabled) continue
                tick++

                val kbps = traffic.sampleKBps()
                Status.kbps = kbps
                Status.screenOn = display.isScreenOn
                Status.idleMillis = SystemClock.elapsedRealtime() - lastActiveAt

                when {
                    kbps >= prefs.wakeKBps -> {
                        markActive()
                        // Before the first exact broadcast, traffic remains the fallback
                        // wake path (needed by senders such as Douyin live). Once this
                        // receiver has proved it broadcasts exact state, never let stale
                        // post-session traffic turn the panel straight back on.
                        val wakeSuppressed = SystemClock.elapsedRealtime() < suppressTrafficWakeUntil
                        if (!display.isScreenOn && !videoStalled && !wakeSuppressed &&
                            !sawSessionBroadcast
                        ) {
                            wakeNow("traffic ${kbps.toInt()} KB/s")
                        }
                    }
                    kbps >= prefs.idleKBps -> markActive()
                }

                maybeSleep()
                if (tick % PROBE_EVERY_TICKS == 0L) checkReceiverAlive()
                if (tick % NOTIFY_EVERY_TICKS == 0L) updateNotification()
            }
        }
    }

    private fun markActive() {
        lastActiveAt = SystemClock.elapsedRealtime()
    }

    /**
     * Black card + countdown, then sleep. Skipped when the screen is already off, and
     * when the user has turned the behaviour off entirely.
     */
    private fun beginCountdown(headline: String) {
        val seconds = prefs.countdownSeconds
        if (seconds <= 0 || !prefs.autoSleep) return
        if (!display.isScreenOn) return
        if (countdown.isShowing) return
        Log.i(TAG, "$headline — ${seconds}s countdown to sleep")
        Status.lastSleepMethod = "倒计时中"
        countdown.start(headline, seconds) {
            Log.i(TAG, "countdown finished — sleeping the panel")
            suppressTrafficWakeUntil =
                SystemClock.elapsedRealtime() + POST_SLEEP_WAKE_SUPPRESSION_MS
            Status.lastSleepMethod = display.sleep().name
            markActive()
        }
    }

    private fun wakeNow(reason: String) {
        Log.i(TAG, "waking screen: $reason")
        Status.lastWakeReason = reason
        markActive()
        // WakeActivity briefly owns the foreground window and therefore destroys the
        // receiver's SurfaceView on this Sony. It is needed only to light a sleeping
        // panel; when the panel is already on, the receiver is already responsible for
        // bringing its own activity forward and must keep its video surface intact.
        if (display.isScreenOn) {
            Log.i(TAG, "screen already on; launching receiver without WakeActivity")
            prefs.receiverPackage.takeIf { probe.isInstalled(it) }?.let { probe.launch(it) }
            return
        }
        display.wake(prefs.receiverPackage.takeIf { probe.isInstalled(it) })
    }

    private fun maybeSleep() {
        if (!prefs.autoSleep) return
        if (!display.isScreenOn) return
        if (prefs.sleepOnlyAfterMirror && !display.wokenByGuard) return

        if (countdown.isShowing) return // its own timer owns the decision

        val idleFor = SystemClock.elapsedRealtime() - lastActiveAt
        if (idleFor < prefs.idleMinutes * 60_000L) return

        Log.i(TAG, "idle for ${idleFor / 60_000} min — sleeping the panel")
        Status.lastSleepMethod = display.sleep().name
        markActive() // don't re-fire the moment the screen reports itself on again
    }

    /**
     * A receiver that has been killed stops answering discovery and the phone just sees
     * nothing. Relaunching does not light the panel.
     */
    private fun checkReceiverAlive() {
        // A TCP connect to the AirPlay port is observable as a real client connection by
        // the receiver. During playback it consequently runs video_flush, which produces
        // an audible glitch every probe interval. A live session is itself proof that the
        // receiver is alive, so never probe until the session has ended.
        if (sessionActive) {
            Status.receiverState = "投屏中（暂停端口探活）"
            return
        }
        val pkg = prefs.receiverPackage
        if (!probe.isInstalled(pkg)) {
            Status.receiverState = "未安装"
            return
        }
        if (probe.isListening(prefs.receiverPort)) {
            Status.receiverState = "监听中 :${prefs.receiverPort}"
            return
        }
        Status.receiverState = "端口无响应，正在重启"
        Log.w(TAG, "$pkg is not listening on ${prefs.receiverPort} — relaunching")
        probe.launch(pkg)
    }

    // -------------------------------------------------------- notification

    private fun registerSessionReceiver() {
        val filter = IntentFilter(SESSION_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sessionReceiver, filter)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val text = "%.0f KB/s · 屏幕%s · %s".format(
            Status.kbps,
            if (Status.screenOn) "亮" else "灭",
            Status.receiverState
        )
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /** Snapshot for the status screen. Deliberately plain: it is only ever displayed. */
    object Status {
        @Volatile var kbps: Double = 0.0
        @Volatile var screenOn: Boolean = true
        @Volatile var idleMillis: Long = 0
        @Volatile var receiverState: String = "尚未检查"
        @Volatile var wakeSource: String = "流量兜底（尚未收到会话广播）"
        @Volatile var lastWakeReason: String = "—"
        @Volatile var lastSleepMethod: String = "—"
    }

    companion object {
        const val ACTION_SLEEP_NOW = "dev.frank.airplayguard.SLEEP_NOW"
        const val ACTION_WAKE_NOW = "dev.frank.airplayguard.WAKE_NOW"

        /** Sent by the patched receiver app when a mirroring session starts or stops. */
        const val SESSION_ACTION = "dev.frank.airplayguard.SESSION"

        private const val CHANNEL_ID = "guard"
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 1_000L
        private const val PROBE_EVERY_TICKS = 30L
        private const val NOTIFY_EVERY_TICKS = 10L
        private const val POST_SLEEP_WAKE_SUPPRESSION_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
