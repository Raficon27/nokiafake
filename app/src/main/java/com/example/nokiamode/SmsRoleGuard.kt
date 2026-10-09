package com.example.nokiamode

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony

/** The safe mode must not own the SMS inbox or its required incoming-message writes. */
internal object SmsRoleGuard {
    fun isHeld(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }
}
