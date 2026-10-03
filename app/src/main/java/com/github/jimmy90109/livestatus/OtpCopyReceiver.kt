package com.github.jimmy90109.livestatus

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("otp_code") ?: return
        val notificationId = intent.getIntExtra("notification_id", 9001)

        // 1. Copy to clipboard
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("OTP Code", code)
        clipboard?.setPrimaryClip(clip)

        Toast.makeText(context, "Copied $code", Toast.LENGTH_SHORT).show()

        // 2. Dismiss the status bar capsule immediately
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(notificationId)
  
    }
}
