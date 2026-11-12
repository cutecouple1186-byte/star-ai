package com.noor.assistant

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Jarvis jaise chhote kaam: namaz, phone dhoondna, focus, gallery, battery. */
object Jarvis {
    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private val names = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    fun city(ctx: Context): String = ctx.getSharedPreferences("noor", Context.MODE_PRIVATE).getString("city", "Karachi")!!.ifBlank { "Karachi" }

    /** Namaz ke waqt (blocking, thread mein chalayein). Din mein ek baar internet se, baad mein cache se. */
    fun prayerTimes(ctx: Context): Map<String, String>? {
        val p = ctx.getSharedPreferences("noor", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())
        val key = today + city(ctx)
        if (p.getString("prayer_key", "") == key) {
            try {
                val j = JSONObject(p.getString("prayer_json", "{}")!!)
                return names.associateWith { j.getString(it) }
            } catch (e: Exception) {
            }
        }
        return try {
            val u = "https://api.aladhan.com/v1/timingsByCity?city=" + Uri.encode(city(ctx)) + "&country=Pakistan&method=1&school=1"
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            val t = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("data").getJSONObject("timings")
            val out = JSONObject()
            for (n in names) out.put(n, t.getString(n).take(5))
            p.edit().putString("prayer_key", key).putString("prayer_json", out.toString()).apply()
            names.associateWith { out.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun prayerLabel(n: String) = when (n) {
        "Fajr" -> "Fajr"; "Dhuhr" -> "Zuhr"; "Asr" -> "Asr"; "Maghrib" -> "Maghrib"; else -> "Isha"
    }

    fun prayerText(ctx: Context, done: (String) -> Unit) {
        Thread {
            val t = prayerTimes(ctx)
            main.post {
                done(
                    if (t == null) "Namaz ke waqt nahi mil sake, internet check karein."
                    else city(ctx) + " ke namaz ke waqt: " + names.joinToString(", ") { prayerLabel(it) + " " + t[it] }
                )
            }
        }.start()
    }

    /** Agar abhi kisi namaz ka waqt hai (aur aaj bataya nahi) to uska naam. Blocking. */
    fun duePrayer(ctx: Context): String? {
        val p = ctx.getSharedPreferences("noor", Context.MODE_PRIVATE)
        val now = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date())
        val today = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())
        val t = prayerTimes(ctx) ?: return null
        for (n in names) {
            if (t[n] == now && p.getString("pray_last", "") != today + n) {
                p.edit().putString("pray_last", today + n).apply()
                return prayerLabel(n)
            }
        }
        return null
    }

    fun findPhone(ctx: Context): String {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            mp?.release()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val m = MediaPlayer()
            m.setAudioStreamType(AudioManager.STREAM_ALARM)
            m.setDataSource(ctx, uri)
            m.isLooping = true
            m.prepare()
            m.start()
            mp = m
            Actions.torchBlink(ctx)
            main.postDelayed({ stopFind() }, 30000)
            return "Phone baj raha hai, 30 second tak."
        } catch (e: Exception) {
            return "Phone ko bajane mein masla hua."
        }
    }

    fun stopFind() {
        try {
            mp?.stop()
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
    }

    fun focus(ctx: Context, on: Boolean): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            try {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
            return "Focus ke liye Noor ko Do Not Disturb access dein, maine settings khol di hain."
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (on) "Focus mode on, ab koi disturb nahi karega." else "Focus mode band."
    }

    /** latest / today / yesterday. */
    fun gallery(ctx: Context, whenTxt: String): String {
        try {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
            var from = 0L
            var to = Long.MAX_VALUE / 1000
            val w = whenTxt.lowercase()
            if (w.contains("yester") || w.contains("kal")) {
                to = cal.timeInMillis / 1000
                from = to - 86400
            } else if (w.contains("today") || w.contains("aaj")) {
                from = cal.timeInMillis / 1000
            }
            val c = ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                MediaStore.Images.Media.DATE_ADDED + " >= ? AND " + MediaStore.Images.Media.DATE_ADDED + " < ?",
                arrayOf(from.toString(), to.toString()),
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )
            val id = c?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
            if (id < 0) return "Is waqt ki koi photo nahi mili (ya Photos ki ijazat do)."
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return "Ye lijiye, photo khol di."
        } catch (e: Exception) {
            return "Photo nahi khul saki, Photos ki ijazat dein."
        }
    }

    fun goodNight(ctx: Context): String {
        val r = focus(ctx, true)
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {
        }
        main.postDelayed({ NoorAccess.screen("lock", "", 50, 50) }, 4000)
        return "Shab bakhair! " + (if (r.contains("access")) r else "Focus on, screen band kar rahi hoon.")
    }

    /** proactiveTick se: batti/storage/neend ki yaad. null = kuch nahi kehna. */
    fun nudge(ctx: Context): String? {
        val p = ctx.getSharedPreferences("noor", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        try {
            val b = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                val bucket = if (lvl <= 5) 5 else if (lvl <= 15) 15 else 100
                if (charging || bucket == 100) p.edit().putInt("bat_warn", 100).apply()
                else if (p.getInt("bat_warn", 100) > bucket) {
                    p.edit().putInt("bat_warn", bucket).apply()
                    return "Battery sirf $lvl percent reh gayi hai, charge par laga dein."
                }
            }
        } catch (e: Exception) {
        }
        try {
            if (p.getString("storage_day", "") != today) {
                val s = StatFs(android.os.Environment.getDataDirectory().path)
                val gb = s.availableBytes / 1073741824.0
                if (gb < 1.0) {
                    p.edit().putString("storage_day", today).apply()
                    return "Phone ki jagah kam hai, sirf " + String.format(Locale.ENGLISH, "%.1f", gb) + " GB bachi hai. Kuch purani cheezen hata dein."
                }
            }
        } catch (e: Exception) {
        }
        if (hour in 1..4 && p.getString("sleep_day", "") != today) {
            p.edit().putString("sleep_day", today).apply()
            return "Bahut raat ho gayi hai, ab so jayein. Sehat zaroori hai."
        }
        if (hour == 14 && p.getString("food_day", "") != today) {
            p.edit().putString("food_day", today).apply()
            return "Kya aap ne khana khaya? Pani bhi piyen."
        }
        return null
    }

    /** Mausam (blocking). Misal: "31°C, saaf". */
    fun weather(ctx: Context): String? {
        val p = ctx.getSharedPreferences("noor", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("wx_at", 0) < 900000 && p.getString("wx_city", "") == city(ctx)) return p.getString("wx", null)
        return try {
            val g = URL("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + Uri.encode(city(ctx))).openConnection() as HttpURLConnection
            g.connectTimeout = 8000; g.readTimeout = 8000
            val r = JSONObject(g.inputStream.bufferedReader().use { it.readText() }).getJSONArray("results").getJSONObject(0)
            val f = URL("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&latitude=" + r.getDouble("latitude") + "&longitude=" + r.getDouble("longitude")).openConnection() as HttpURLConnection
            f.connectTimeout = 8000; f.readTimeout = 8000
            val cur = JSONObject(f.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            val code = cur.getInt("weather_code")
            val d = when {
                code == 0 -> "saaf"; code <= 3 -> "badal"; code in 45..48 -> "dhund"; code in 51..67 -> "barish"
                code in 71..77 -> "baraf"; code in 80..82 -> "barish"; code >= 95 -> "toofan"; else -> "mausam"
            }
            val out = Math.round(cur.getDouble("temperature_2m")).toString() + "°C, " + d
            p.edit().putLong("wx_at", now).putString("wx_city", city(ctx)).putString("wx", out).apply()
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Agli namaz (blocking). Misal: "Asr 15:42". */
    fun nextPrayer(ctx: Context): String? {
        val t = prayerTimes(ctx) ?: return null
        val now = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date())
        for (n in names) if (t[n]!! > now) return prayerLabel(n) + " " + t[n]
        return prayerLabel("Fajr") + " " + t["Fajr"]
    }

    /** Awaaz tez: media volume 92% tak. */
    fun loud(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < max * 0.92) am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.92).toInt(), 0)
        } catch (e: Exception) {
        }
    }
}
