package dev.frank.airplayguard

import android.content.Context
import android.content.SharedPreferences

/**
 * Everything the guard is allowed to be tuned with, in one place.
 *
 * Defaults are picked for a TV that sits in standby all day on a wired or 5 GHz
 * link: idle chatter (mDNS, NTP, the launcher phoning home) stays well under
 * [idleKBps], while an AirPlay mirroring session runs at several hundred KB/s
 * from its first video frame.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("guard", Context.MODE_PRIVATE)

    /** Package name of the AirPlay receiver app we babysit. */
    var receiverPackage: String
        get() = sp.getString(KEY_RECEIVER, DEFAULT_RECEIVER) ?: DEFAULT_RECEIVER
        set(value) = sp.edit().putString(KEY_RECEIVER, value).apply()

    /** Port the receiver listens on; used as a liveness probe. AirPlay RTSP is 7000. */
    var receiverPort: Int
        get() = sp.getInt(KEY_PORT, 7000)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    /**
     * Seconds the black countdown card is shown after a session ends or the sender's
     * screen locks, before the panel is switched off. Ending a session is a far stronger
     * "I'm done" signal than mere idleness, so this is seconds rather than [idleMinutes],
     * with the card giving you a chance to cancel. 0 disables the whole behaviour.
     */
    var countdownSeconds: Int
        get() = sp.getInt(KEY_COUNTDOWN, 10)
        set(value) = sp.edit().putInt(KEY_COUNTDOWN, value).apply()

    /** Minutes of no traffic before the panel is put back to sleep. */
    var idleMinutes: Int
        get() = sp.getInt(KEY_IDLE_MIN, 60)
        set(value) = sp.edit().putInt(KEY_IDLE_MIN, value).apply()

    /**
     * Sustained download rate (KB/s) that counts as "a mirroring session started".
     * Must sit well above an AirPlay *audio-only* stream, which measured ~205 KB/s on
     * this TV while the sender's screen was locked — at 150 it woke the panel back up
     * nine seconds after the guard had just put it to sleep. Mirroring runs ~1800 KB/s.
     */
    var wakeKBps: Int
        get() = sp.getInt(KEY_WAKE_KBPS, 700)
        set(value) = sp.edit().putInt(KEY_WAKE_KBPS, value).apply()

    /** Download rate (KB/s) below which the TV is considered to be doing nothing. */
    var idleKBps: Int
        get() = sp.getInt(KEY_IDLE_KBPS, 30)
        set(value) = sp.edit().putInt(KEY_IDLE_KBPS, value).apply()

    /**
     * Degrees the countdown card is rotated by, for a panel mounted sideways. Applied as
     * a View rotation (clockwise-positive), which is the opposite sense to the receiver's
     * GL "Picture rotation" — so a receiver set to 90 pairs with 270 here. Cycle it from
     * the status screen until the text reads upright.
     */
    var overlayRotation: Int
        get() = sp.getInt(KEY_OVERLAY_ROTATION, 270)
        set(value) = sp.edit().putInt(KEY_OVERLAY_ROTATION, value).apply()

    /** Master switch. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Turn the panel off on idle. Off = wake-on-AirPlay only. */
    var autoSleep: Boolean
        get() = sp.getBoolean(KEY_AUTO_SLEEP, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SLEEP, value).apply()

    /**
     * Only auto-sleep if *we* were the ones who turned the screen on. Safer if you
     * also use the TV normally: it can never black out a session the guard didn't start.
     */
    var sleepOnlyAfterMirror: Boolean
        get() = sp.getBoolean(KEY_ONLY_AFTER_MIRROR, false)
        set(value) = sp.edit().putBoolean(KEY_ONLY_AFTER_MIRROR, value).apply()

    private companion object {
        const val DEFAULT_RECEIVER = "io.github.jqssun.airplay"
        const val KEY_RECEIVER = "receiver_package"
        const val KEY_PORT = "receiver_port"
        const val KEY_IDLE_MIN = "idle_minutes"
        const val KEY_COUNTDOWN = "countdown_seconds"
        const val KEY_OVERLAY_ROTATION = "overlay_rotation"
        const val KEY_WAKE_KBPS = "wake_kbps"
        const val KEY_IDLE_KBPS = "idle_kbps"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_SLEEP = "auto_sleep"
        const val KEY_ONLY_AFTER_MIRROR = "sleep_only_after_mirror"
    }
}
