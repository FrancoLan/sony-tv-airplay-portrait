package dev.frank.airplayguard.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import dev.frank.airplayguard.TAG
import android.util.Log

/**
 * A black, one-shot activity whose only purpose is to carry FLAG_TURN_SCREEN_ON — the
 * one mechanism an ordinary app still has for lighting a sleeping panel. It hands focus
 * to the AirPlay receiver and gets out of the way.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val pkg = intent?.getStringExtra(EXTRA_LAUNCH_PACKAGE)
        // A beat for the panel to actually come up before we hand over, otherwise some
        // firmwares drop the incoming activity on the floor.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!pkg.isNullOrEmpty()) launchReceiver(pkg)
            finish()
        }, HANDOFF_DELAY_MS)
    }

    private fun launchReceiver(pkg: String) {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "no launch intent for $pkg")
            return
        }
        // This Sony destroys the receiver window's Surface when WakeActivity takes
        // focus from a sleeping panel. Merely bringing the singleTop receiver activity
        // forward leaves it resumed with NO_SURFACE, producing a permanently black
        // screen. Recreate only the receiver task after the panel is awake; its
        // foreground AirPlayService stays alive and attaches the session to the new
        // SurfaceView.
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "could not bring $pkg forward", it) }
    }

    companion object {
        const val EXTRA_LAUNCH_PACKAGE = "launch_package"
        private const val HANDOFF_DELAY_MS = 600L
    }
}
