package dev.frank.airplayguard.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.frank.airplayguard.GuardService
import dev.frank.airplayguard.R
import dev.frank.airplayguard.Prefs
import dev.frank.airplayguard.detect.ReceiverProbe
import dev.frank.airplayguard.power.DisplayController

/**
 * Status and controls. Built in code rather than XML because it is a handful of
 * D-pad-focusable rows and nothing about it needs a layout editor.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var display: DisplayController
    private lateinit var probe: ReceiverProbe
    private lateinit var status: TextView
    private var firstButton: Button? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = statusText()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        display = DisplayController(this)
        probe = ReceiverProbe(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }

        root.addView(title("AirPlay 待机管家"))
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#C8D4E4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        root.addView(status)
        root.addView(spacer())

        root.addView(button("立即熄屏") {
            startService(Intent(this, GuardService::class.java).setAction(GuardService.ACTION_SLEEP_NOW))
        }.also { firstButton = it })
        root.addView(button("唤醒并打开接收端") {
            startService(Intent(this, GuardService::class.java).setAction(GuardService.ACTION_WAKE_NOW))
        })
        // 1 minute is a test setting: it makes the idle timer observable in a sitting
        // instead of an hour. Cycles back round to the real values.
        root.addView(button("空闲熄屏：切换 1(测试) / 30 / 60 / 120 分钟") {
            prefs.idleMinutes = when (prefs.idleMinutes) {
                1 -> 30
                30 -> 60
                60 -> 120
                else -> 1
            }
        })
        root.addView(button("提示文字旋转：切换 0 / 90 / 180 / 270°") {
            prefs.overlayRotation = (prefs.overlayRotation + 90) % 360
            GuardService.start(this)
        })
        root.addView(button("结束/锁屏倒计时：切换 5 / 10 / 30 秒 / 关") {
            prefs.countdownSeconds = when (prefs.countdownSeconds) {
                5 -> 10
                10 -> 30
                30 -> 0
                else -> 5
            }
        })
        root.addView(button("自动熄屏：开 / 关") { prefs.autoSleep = !prefs.autoSleep })
        root.addView(button("重启守护服务") { GuardService.start(this) })
        root.addView(button("打开系统「其他应用上层显示」设置") { openOverlaySettings() })

        setContentView(ScrollView(this).apply { addView(root) })
        firstButton?.requestFocus()
        GuardService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun statusText(): String {
        val s = GuardService.Status
        val pkg = prefs.receiverPackage
        val idleMin = s.idleMillis / 60_000
        val overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        return buildString {
            appendLine("接收端     ${if (probe.isInstalled(pkg)) pkg else "$pkg（未安装）"}")
            appendLine("           ${s.receiverState}")
            appendLine()
            appendLine("当前流量   %.0f KB/s   （唤醒阈值 ${prefs.wakeKBps} KB/s）".format(s.kbps))
            appendLine("唤醒依据   ${s.wakeSource}")
            appendLine("提示旋转   ${prefs.overlayRotation}°")
            appendLine("屏幕       ${if (s.screenOn) "亮" else "灭"}   空闲 ${idleMin} 分钟 / ${prefs.idleMinutes} 分钟")
            appendLine("自动熄屏   ${if (prefs.autoSleep) "开" else "关"}")
            appendLine("结束/锁屏后 ${if (prefs.countdownSeconds > 0) "${prefs.countdownSeconds} 秒倒计时后熄屏" else "不处理"}")
            appendLine()
            appendLine("熄屏方式   ${methodLabel(display.sleepMethod())}")
            appendLine("上次唤醒   ${s.lastWakeReason}")
            appendLine()
            appendLine("设备管理员 ${if (display.isAdminActive) "已激活" else "未激活 —— 需要用 adb 授权"}")
            append("上层显示   ${if (overlayOk) "已授权" else "未授权 —— 需要用 adb 授权"}")
        }
    }

    private fun methodLabel(m: DisplayController.Method): String = when (m) {
        DisplayController.Method.DEVICE_ADMIN -> "设备管理员锁屏（真熄屏，推荐）"
        DisplayController.Method.ROOT_KEYEVENT -> "root 模拟休眠键（真熄屏）"
        DisplayController.Method.BLACK_OVERLAY -> "全黑遮罩（背光仍亮，降级方案）"
        DisplayController.Method.NONE -> "无可用方式 —— 请先用 adb 授权"
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT < 23) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }
    }

    // ------------------------------------------------------------- widgets

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setPadding(0, 0, 0, dp(20))
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isFocusable = true
        isFocusableInTouchMode = true
        // The stock TV button gives almost no focus feedback on this firmware, so the
        // D-pad selection is invisible. These make the focused row unmistakable.
        setBackgroundResource(R.drawable.btn_bg)
        // the theme-aware overload is API 23; this one works back to minSdk and the
        // selector uses literal colours, so there is nothing for a theme to resolve
        @Suppress("DEPRECATION")
        setTextColor(resources.getColorStateList(R.color.btn_text))
        setPadding(dp(20), dp(14), dp(20), dp(14))
        stateListAnimator = null
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f).setDuration(120).start()
        }
        setOnClickListener { onClick(); status.text = statusText() }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
