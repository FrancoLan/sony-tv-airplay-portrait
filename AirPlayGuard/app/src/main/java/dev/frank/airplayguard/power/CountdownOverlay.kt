package dev.frank.airplayguard.power

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.frank.airplayguard.TAG

/**
 * The black "this is about to switch off" card.
 *
 * Two things end a mirroring session from the viewer's point of view: stopping it, and
 * locking the phone. Neither should leave the TV sitting on a frozen frame or on the
 * receiver's own UI, but nor should the panel just die without saying why — so we cover
 * the screen in black, say what happened in the corner, and count down. Any remote key
 * cancels, which is the only way back if the guess was wrong.
 */
class CountdownOverlay(
    private val context: Context,
    /** Degrees to rotate the card by, so the text reads upright on a sideways panel. */
    private val rotationProvider: () -> Int
) {

    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var label: TextView? = null
    private var onFinish: (() -> Unit)? = null
    private var remaining = 0

    val isShowing: Boolean get() = root != null

    private val tick = object : Runnable {
        override fun run() {
            remaining--
            if (remaining <= 0) {
                val done = onFinish
                // Keep the opaque card in front while the sleep request is issued. If
                // it is removed first, the receiver's settings screen flashes briefly
                // before DevicePolicyManager has switched the panel off.
                main.removeCallbacks(this)
                onFinish = null
                done?.invoke()
                main.postDelayed({ dismissInternal() }, DISMISS_AFTER_SLEEP_MS)
                return
            }
            label?.text = text(headline, remaining)
            main.postDelayed(this, 1_000L)
        }
    }

    private var headline: String = ""

    /**
     * @param seconds counted down before [onFinish] runs. Restarting an already visible
     *   countdown just relabels it, so a stall report arriving twice doesn't stack timers.
     */
    fun start(headline: String, seconds: Int, onFinish: () -> Unit) = main.post {
        this.headline = headline
        this.onFinish = onFinish
        this.remaining = seconds

        if (root == null) {
            if (!canDraw()) {
                // Without the overlay permission there is no card to show; the caller's
                // action still needs to happen, so run it now rather than silently stall.
                Log.w(TAG, "no overlay permission — skipping countdown card")
                onFinish()
                return@post
            }
            build()
        }
        label?.text = text(headline, seconds)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1_000L)
    }

    /** Cancels without running the finish action. */
    fun cancel() = main.post {
        if (root == null) return@post
        Log.i(TAG, "countdown cancelled")
        dismissInternal()
    }

    private fun dismissInternal() {
        main.removeCallbacks(tick)
        onFinish = null
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        label = null
    }

    private fun text(headline: String, seconds: Int) =
        "$headline\n$seconds 秒后关闭屏幕 · 按任意键取消"

    private fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    @SuppressLint("InlinedApi")
    private fun build() {
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
        )

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        fun dp(v: Int) = (v * density).toInt()

        val text = TextView(context).apply {
            setTextColor(Color.parseColor("#8FA3BF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setLineSpacing(dp(6).toFloat(), 1f)
        }

        // The TV is mounted sideways, so the card is laid out in the VIEWER's frame and
        // then rotated as a whole. A quarter turn swaps the page's dimensions; rotating a
        // (h x w) box about its centre lands exactly on the (w x h) screen.
        val rotation = normalise(rotationProvider())
        val quarter = rotation == 90 || rotation == 270
        val pageW = if (quarter) metrics.heightPixels else metrics.widthPixels
        val pageH = if (quarter) metrics.widthPixels else metrics.heightPixels

        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(56), dp(44), dp(56), dp(44))
            addView(text)
            layoutParams = FrameLayout.LayoutParams(pageW, pageH, Gravity.CENTER)
            this.rotation = rotation.toFloat()
        }

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
            addView(page)
            setOnKeyListener { _, _, _ -> cancel(); true }
        }

        runCatching {
            windows.addView(container, params)
            container.requestFocus()
            root = container
            label = text
        }.onFailure {
            Log.w(TAG, "countdown overlay failed", it)
            root = null
            label = null
        }
    }

    private fun normalise(deg: Int) = (((deg % 360) + 360) % 360) / 90 * 90

    private companion object {
        const val DISMISS_AFTER_SLEEP_MS = 1_000L
    }
}
