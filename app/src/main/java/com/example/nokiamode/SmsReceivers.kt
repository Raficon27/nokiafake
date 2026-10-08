package com.example.nokiamode

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.widget.Toast

/** Android delivers new SMS messages to the selected default SMS application. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_DELIVER") return
        val extras = intent.extras ?: return
        val pdus = extras.get("pdus") as? Array<*> ?: return
        val format = extras.getString("format")
        val messages = pdus.mapNotNull { raw ->
            try {
                val bytes = raw as? ByteArray ?: return@mapNotNull null
                SmsMessage.createFromPdu(bytes, format)
            } catch (_: Exception) { null }
        }
        if (messages.isEmpty()) return
        val address = messages.first().originatingAddress ?: ""
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val values = ContentValues().apply {
            put("address", address); put("body", body); put("date", System.currentTimeMillis())
            put("read", 0); put("seen", 0); put("type", 1)
        }
        try { context.contentResolver.insert(Uri.parse("content://sms/inbox"), values) } catch (_: Exception) { }
    }
}

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Toast.makeText(context, "הודעות MMS אינן נתמכות בגרסה זו", Toast.LENGTH_LONG).show()
    }
}

/** Handles SMS replies launched by other Android applications. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data
        val address = uri?.schemeSpecificPart?.substringBefore('?')
        val body = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (!address.isNullOrBlank() && body.isNotBlank()) {
            try { SmsManager.getDefault().sendTextMessage(address, null, body, null, null) } catch (_: Exception) { }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
