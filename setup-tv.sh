#!/usr/bin/env bash
# One-shot provisioning for the TV over adb.
#
# Android TV has no on-screen UI for several of the permissions this needs, so they
# are granted from here instead. Everything below is reversible; see the bottom of
# the script for the undo commands.
set -euo pipefail

TV_IP="${1:-}"
GUARD_PKG="dev.frank.airplayguard"
RECEIVER_PKG="io.github.jqssun.airplay"

say()  { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m  ! \033[0m%s\n' "$*"; }
ok()   { printf '\033[1;32m  ok\033[0m %s\n' "$*"; }
try()  { if adb shell "$@" >/dev/null 2>&1; then ok "$*"; else warn "失败（可忽略）: $*"; fi; }

[ -n "$TV_IP" ] || { echo "用法: ./setup-tv.sh <电视IP>    例如 ./setup-tv.sh 192.168.1.100"; exit 1; }

say "连接电视 $TV_IP"
adb connect "$TV_IP:5555"
adb wait-for-device
adb shell getprop ro.product.model

say "安装 APK"
if [ -f airplay-portrait/airplay-portrait.apk ]; then
  adb install -r airplay-portrait/airplay-portrait.apk && ok "接收端（竖屏版）"
else
  warn "airplay-portrait/airplay-portrait.apk 不存在，先跑 airplay-portrait/build.sh"
fi
if [ -f AirPlayGuard/app/build/outputs/apk/release/app-release.apk ]; then
  adb install -r AirPlayGuard/app/build/outputs/apk/release/app-release.apk && ok "待机管家"
else
  warn "待机管家 APK 不存在，先跑 cd AirPlayGuard && ./gradlew assembleRelease"
fi

say "授予待机管家所需权限"
# Background activity launch + the black-overlay fallback.
try appops set "$GUARD_PKG" SYSTEM_ALERT_WINDOW allow
# Lets the app own the screen-off timeout so the platform timer doesn't fight it.
try pm grant "$GUARD_PKG" android.permission.WRITE_SECURE_SETTINGS
# The only no-root way to genuinely sleep the panel.
try dpm set-active-admin "$GUARD_PKG/.power.GuardAdminReceiver"

say "关掉会和管家打架的系统计时器"
# Platform screen-off timer off: the guard decides when to sleep.
try settings put secure sleep_timeout -1
# Android 11+ "inattentive sleep" — would black the TV out mid-session.
try settings put secure attentive_timeout -1

say "让两个 App 免受电池优化 / 后台限制"
try dumpsys deviceidle whitelist "+$GUARD_PKG"
try dumpsys deviceidle whitelist "+$RECEIVER_PKG"

say "当前状态"
adb shell dumpsys deviceidle whitelist 2>/dev/null | grep -E "airplay" || true
echo
adb shell dpm list-owners 2>/dev/null || true

say "完成"
cat <<'NEXT'
电视上还要手动做两件事（遥控器）：

  1. 设置 → 网络和互联网 → 远程启动 (Remote start) → 开
     这是待机时网络不断的开关，手机才能在电视熄屏时发现它。

  2. 设置 → Apple AirPlay 和 HomeKit → AirPlay → 关
     索尼原生接收端不能旋转画面，关掉它可以避免手机上出现两个同名设备。
     （HomeKit 保持开启不受影响。）

撤销本脚本：
  adb shell dpm remove-active-admin dev.frank.airplayguard/.power.GuardAdminReceiver
  adb shell appops set dev.frank.airplayguard SYSTEM_ALERT_WINDOW default
  adb shell settings put secure sleep_timeout 900000
NEXT
