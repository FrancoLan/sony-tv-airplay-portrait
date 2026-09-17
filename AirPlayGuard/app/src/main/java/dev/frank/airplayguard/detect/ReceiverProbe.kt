package dev.frank.airplayguard.detect

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.frank.airplayguard.TAG
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Keeps the AirPlay receiver app actually listening.
 *
 * An app that has been swiped away or killed for memory stops answering discovery, and
 * the phone simply shows nothing — the most common reason "the TV disappeared". We
 * check by connecting to its RTSP port on loopback (no permission needed, unlike
 * /proc/net which has been UID-scoped since Android 10) and relaunch it if the port is
 * dead. Relaunching does not light the panel: only WakeActivity does that.
 */
class ReceiverProbe(private val context: Context) {

    fun isListening(port: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrElse { false }

    fun isInstalled(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrElse { false }

    /** Starts the receiver's launcher activity in the background. */
    fun launch(pkg: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return runCatching { context.startActivity(intent); true }
            .getOrElse {
                // Background activity starts need SYSTEM_ALERT_WINDOW on Android 10+.
                Log.w(TAG, "could not launch $pkg — grant SYSTEM_ALERT_WINDOW over adb", it)
                false
            }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 400
    }
}
