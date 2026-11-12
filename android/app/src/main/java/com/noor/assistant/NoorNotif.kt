package com.noor.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Aane wali notifications yaad rakhta hai taake Noor "kis ka msg aaya" bata sake. */
class NoorNotif : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName || sbn.isOngoing) return
            val e = sbn.notification.extras
            val title = e.getCharSequence("android.title")?.toString() ?: ""
            val text = (e.getCharSequence("android.bigText") ?: e.getCharSequence("android.text"))?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) return
            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (x: Exception) {
                sbn.packageName
            }
            synchronized(recent) {
                recent.add(Triple(app, title, text.take(160)))
                while (recent.size > 25) recent.removeAt(0)
            }
        } catch (x: Exception) {
        }
    }

    companion object {
        val recent = ArrayList<Triple<String, String, String>>()

        fun summary(n: Int = 6): String {
            val l = synchronized(recent) { recent.takeLast(n).reversed() }
            if (l.isEmpty()) return "Koi nayi notification nahi mili. Agar pehle kabhi nahi chali to Settings mein Notification access ON karein."
            return l.joinToString(". ") { (a, t, x) -> "$a: " + listOf(t, x).filter { it.isNotBlank() }.joinToString(" - ") }
        }
    }
}
