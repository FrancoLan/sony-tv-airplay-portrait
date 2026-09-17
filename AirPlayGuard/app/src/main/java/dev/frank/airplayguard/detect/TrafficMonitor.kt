package dev.frank.airplayguard.detect

import android.net.TrafficStats
import android.os.SystemClock
import java.io.File

/**
 * Tells the guard whether anything is streaming, using nothing but device-wide byte
 * counters — no permissions, no root, real-time.
 *
 * Why device-wide and not per-app: [TrafficStats.getUidRxBytes] stopped reporting other
 * apps' UIDs in Android 7, and NetworkStatsManager only flushes its buckets every few
 * minutes, which is useless for a 1 s loop. Device-wide is crude but decisive here — a
 * TV in standby moves a few KB/min, an AirPlay mirror moves several hundred KB/s.
 */
class TrafficMonitor {

    private var lastBytes = -1L
    private var lastAt = 0L

    /** Download rate since the previous call, in KB/s. Returns 0 on the first sample. */
    fun sampleKBps(): Double {
        val bytes = readRxBytes()
        val now = SystemClock.elapsedRealtime()
        if (bytes < 0) return 0.0

        val prevBytes = lastBytes
        val prevAt = lastAt
        lastBytes = bytes
        lastAt = now

        if (prevBytes < 0 || now <= prevAt) return 0.0
        // Counters reset on interface/boot changes; treat a negative delta as a restart.
        val delta = bytes - prevBytes
        if (delta < 0) return 0.0
        return delta / 1024.0 / ((now - prevAt) / 1000.0)
    }

    fun reset() {
        lastBytes = -1L
        lastAt = 0L
    }

    private fun readRxBytes(): Long {
        val total = TrafficStats.getTotalRxBytes()
        if (total != TrafficStats.UNSUPPORTED.toLong()) return total
        return readRxBytesFromSysfs()
    }

    /** Some TV firmwares report UNSUPPORTED from TrafficStats; sysfs always works. */
    private fun readRxBytesFromSysfs(): Long {
        val root = File("/sys/class/net")
        val nics = root.listFiles() ?: return -1
        var sum = 0L
        var found = false
        for (nic in nics) {
            if (nic.name == "lo") continue
            val f = File(nic, "statistics/rx_bytes")
            val v = runCatching { f.readText().trim().toLong() }.getOrNull() ?: continue
            sum += v
            found = true
        }
        return if (found) sum else -1
    }
}
