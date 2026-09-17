package dev.frank.airplayguard

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * The reason the iPhone can still see the TV while the panel is off.
 *
 * With the screen off, the Wi-Fi driver normally switches on multicast filtering to
 * save power, and mDNS/Bonjour is multicast — so the TV silently stops answering
 * discovery queries even though its AirPlay server is alive. A held
 * [WifiManager.MulticastLock] disables that filtering; a high-perf [WifiManager.WifiLock]
 * stops the radio dropping to a power-save duty cycle that adds seconds of latency;
 * the partial wake lock keeps our own polling loop scheduled.
 *
 * These are device-wide effects, which is why this helps the separate receiver app too.
 */
class NetworkLocks(context: Context) {

    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var multicast: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpu: PowerManager.WakeLock? = null

    fun acquire() {
        if (multicast != null) return
        runCatching {
            multicast = wifi.createMulticastLock("$TAG:mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "$TAG:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "could not take network locks", it) }
    }

    fun release() {
        runCatching { multicast?.release() }
        runCatching { wifiLock?.release() }
        runCatching { cpu?.release() }
        multicast = null
        wifiLock = null
        cpu = null
    }

    val held: Boolean get() = multicast?.isHeld == true
}
