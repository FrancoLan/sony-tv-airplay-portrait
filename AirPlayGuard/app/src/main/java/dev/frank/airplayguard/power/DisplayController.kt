package dev.frank.airplayguard.power

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import dev.frank.airplayguard.TAG
import dev.frank.airplayguard.ui.WakeActivity
import java.io.DataOutputStream

/**
 * Turns the panel on and off on a stock, non-rooted Android TV.
 *
 * Android gives ordinary apps no way to sleep the display (`PowerManager.goToSleep`
 * is signature-only), so we try three mechanisms in descending order of quality:
 *
 *  1. [DevicePolicyManager.lockNow] via an active device admin — a genuine display
 *     sleep, the SoC stays up, the network stays up. Set up once over adb.
 *  2. `input keyevent SLEEP` through `su`, for rooted boxes.
 *  3. A black system overlay with the window brightness pinned to 0 — the panel is
 *     still lit, but the room is dark and everything else keeps working.
 *
 * Waking is the easy direction: a one-shot activity carrying FLAG_TURN_SCREEN_ON.
 */
class DisplayController(private val context: Context) {

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, GuardAdminReceiver::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var overlay: View? = null

    /** True once we have turned the screen on ourselves and not yet slept it again. */
    @Volatile
    var wokenByGuard: Boolean = false
        private set

    val isScreenOn: Boolean
        get() = if (Build.VERSION.SDK_INT >= 20) power.isInteractive else {
            @Suppress("DEPRECATION")
            power.isScreenOn
        }

    val isAdminActive: Boolean
        get() = dpm.isAdminActive(admin)

    fun sleepMethod(): Method = when {
        isAdminActive -> Method.DEVICE_ADMIN
        hasRoot() -> Method.ROOT_KEYEVENT
        canDrawOverlay() -> Method.BLACK_OVERLAY
        else -> Method.NONE
    }

    // ---------------------------------------------------------------- wake

    /**
     * Light the panel up and put the AirPlay receiver in front. Safe to call when the
     * screen is already on: it just refreshes the idle state.
     */
    fun wake(launchPackage: String?) {
        hideOverlay()

        // Deprecated since API 17 but still honoured by most TV firmwares, and it is
        // the only thing that fires before an activity can be composed. Short timeout —
        // WakeActivity's FLAG_KEEP_SCREEN_ON takes over from here.
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "$TAG:wake"
        )
        runCatching { lock.acquire(5_000L) }

        val intent = Intent(context, WakeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(WakeActivity.EXTRA_LAUNCH_PACKAGE, launchPackage)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "WakeActivity start failed", it) }

        wokenByGuard = true
    }

    // --------------------------------------------------------------- sleep

    fun sleep(): Method {
        val method = sleepMethod()
        when (method) {
            Method.DEVICE_ADMIN -> runCatching { dpm.lockNow() }
                .onFailure { Log.w(TAG, "lockNow failed", it); showOverlay() }

            Method.ROOT_KEYEVENT -> if (!shell("input keyevent 223")) showOverlay()

            Method.BLACK_OVERLAY -> showOverlay()

            Method.NONE -> Log.w(TAG, "no way to turn the screen off on this device")
        }
        wokenByGuard = false
        return method
    }

    // ------------------------------------------------------------- overlay

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    @SuppressLint("InlinedApi")
    private fun showOverlay() = main.post {
        if (overlay != null) return@post
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply {
            // 0f = dimmest the panel will go while this window is on top.
            screenBrightness = 0f
        }
        val view = View(context).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, _ -> hideOverlay(); true }
            setOnClickListener { hideOverlay() }
        }
        runCatching {
            windows.addView(view, params)
            view.requestFocus()
            overlay = view
        }.onFailure { Log.w(TAG, "overlay add failed — grant SYSTEM_ALERT_WINDOW over adb", it) }
    }

    fun hideOverlay() = main.post {
        overlay?.let { v ->
            runCatching { windows.removeView(v) }
            overlay = null
        }
    }

    // ---------------------------------------------------------------- misc

    /**
     * Stop the platform's own screen-off timer from fighting ours. Needs
     * WRITE_SECURE_SETTINGS, granted over adb; silently a no-op otherwise.
     */
    fun disableSystemSleepTimeout(): Boolean = runCatching {
        Settings.Secure.putInt(context.contentResolver, "sleep_timeout", -1)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            Int.MAX_VALUE
        )
        true
    }.getOrElse { false }

    // Probed once: on a rooted box `su` may raise a confirmation dialog, and
    // sleepMethod() is called often (status screen, every idle tick).
    private val rooted: Boolean by lazy { shell("id") }

    private fun hasRoot(): Boolean = rooted

    private fun shell(cmd: String): Boolean = runCatching {
        val p = Runtime.getRuntime().exec("su")
        DataOutputStream(p.outputStream).use {
            it.writeBytes("$cmd\nexit\n")
            it.flush()
        }
        p.waitFor() == 0
    }.getOrElse { false }

    enum class Method { DEVICE_ADMIN, ROOT_KEYEVENT, BLACK_OVERLAY, NONE }
}
