package com.example.nokiamode

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/** The platform-supported shade lock, available only after device-owner provisioning. */
class NokiaAdminReceiver : DeviceAdminReceiver()

internal object StatusBarControl {
    private fun manager(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun available(context: Context): Boolean = try {
        manager(context).isDeviceOwnerApp(context.packageName)
    } catch (_: Exception) { false }

    fun enable(context: Context): Boolean = set(context, true)
    fun disable(context: Context): Boolean = set(context, false)

    private fun set(context: Context, disabled: Boolean): Boolean {
        if (!available(context)) return false
        return try {
            manager(context).setStatusBarDisabled(
                ComponentName(context, NokiaAdminReceiver::class.java), disabled)
        } catch (_: SecurityException) { false }
    }
}
