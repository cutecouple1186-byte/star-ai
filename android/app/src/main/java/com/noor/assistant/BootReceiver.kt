package com.noor.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Phone on hote hi Noor ko khud shuru karne ki koshish. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("noor", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", true)) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        // Android 13 aur purane: seedha service shuru ho sakti hai.
        if (Build.VERSION.SDK_INT < 34) {
            try {
                context.startForegroundService(Intent(context, NoorService::class.java))
                return
            } catch (e: Exception) {
            }
        }

        // Android 14+: boot se mic wali service seedha shuru nahi ho sakti.
        // Isliye Noor ki screen khol kar wahan se shuru karte hain (iske liye "dusri app ke upar" ki ijazat chahiye).
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_AUTOSTART, true)
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(open)
                return
            } catch (e: Exception) {
            }
        }

        // Aakhri raasta: notification, ek tap se Noor shuru.
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("noor_boot", "Noor shuru", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(context, "noor_boot")
                .setContentTitle("Noor")
                .setContentText("Noor shuru karne ke liye yahan dabayein")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        } catch (e: Exception) {
        }
    }
}
