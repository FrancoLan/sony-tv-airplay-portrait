package dev.frank.airplayguard.power

import android.app.admin.DeviceAdminReceiver

/**
 * Exists purely so DevicePolicyManager.lockNow() is available to us. We request the
 * `force-lock` policy and nothing else — see res/xml/device_admin.xml.
 *
 * Activate without any on-screen prompt (Android TV often has no admin UI):
 *   adb shell dpm set-active-admin dev.frank.airplayguard/.power.GuardAdminReceiver
 */
class GuardAdminReceiver : DeviceAdminReceiver()
