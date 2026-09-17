package dev.frank.airplayguard.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.frank.airplayguard.GuardService
import dev.frank.airplayguard.Prefs
import dev.frank.airplayguard.TAG

/**
 * Brings the guard back up after a reboot and after the app is updated.
 *
 * The update case matters as much as the reboot one: installing a new APK kills the
 * process, and START_STICKY does not restart a service across a package replacement.
 * Without MY_PACKAGE_REPLACED the guard stays dead after every update, and the only
 * symptom is that one day the TV quietly fails to wake for a mirroring session.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).enabled) return
        Log.i(TAG, "restarting guard after ${intent.action}")
        GuardService.start(context)
    }
}
