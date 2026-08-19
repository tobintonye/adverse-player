package com.adverse.adverseplayer.device

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Grants device-owner status via, one time, before any Google account
 * touches the box:
 *   adb shell dpm set-device-owner com.adverse.player/.device.AppDeviceAdminReceiver
 *
 * Once granted, this is what lets the app:
 *  - lock itself into kiosk/lock-task mode (can't be backed out of)
 *  - trigger a remote reboot without anyone on-site
 *  - silently install APK updates without the Play Store
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun componentName(context: Context) =
            ComponentName(context, AppDeviceAdminReceiver::class.java)

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }

        /** Call once, after confirming isDeviceOwner() is true, to whitelist
         *  this app for lock-task mode. Then call Activity.startLockTask(). */
        fun enableLockTask(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.setLockTaskPackages(componentName(context), arrayOf(context.packageName))
        }

        fun reboot(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.reboot(componentName(context))
            }
        }
    }
}
