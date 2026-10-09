package com.example.nokiamode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** A scheduled alarm is delivered even when Nokia Mode is no longer on screen. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("nokia_alarms", "שעון מעורר", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(context, 1,
            Intent(context, SetupActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, "nokia_alarms")
            .setSmallIcon(R.drawable.ic_nokia)
            .setContentTitle("שעון מעורר")
            .setContentText("הגיע הזמן שקבעת")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        manager.notify(2001, notification)
    }
}
