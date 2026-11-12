package com.noor.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Background mein chalne wali Noor. "Hey Noor" sunti hai, server se jawab leti hai,
 * bolti hai aur phone ke kaam karti hai.
 */
class NoorService : Service() {

    companion object {
        const val CHANNEL = "noor"
        const val ACTION_STOP = "com.noor.assistant.STOP"
        const val ACTION_TEXT = "com.noor.assistant.TEXT"
        const val EXTRA_TEXT = "text"

        @Volatile
        var isRunning = false

        @Volatile
        var instance: NoorService? = null

        fun instanceReady(): Boolean = instance != null

        /** Gallery ki tasveer + sawaal bhejo (img = data:image/jpeg;base64,...). */
        fun submitImage(text: String, img: String) {
            val s = instance ?: return
            s.lastPic = img
            s.main.post {
                s.activeUntil = System.currentTimeMillis() + 60000
                s.stopSpeakingNow()
                try {
                    s.recognizer?.cancel()
                } catch (e: Exception) {
                }
                s.busy = false
                Chat.add("me", "🖼 " + text)
                s.ask(text, img)
            }
        }

        @Volatile
        var liveActive = false

        fun toggleLive() {
            val s = instance ?: return
            s.main.post { if (liveActive) s.stopLive() else s.startLive() }
        }

        /** Mic button: bina "Hey Noor" ke ab bolo. */
        fun talkNow() {
            val s = instance ?: return
            s.main.post {
                s.activeUntil = System.currentTimeMillis() + 60000
                s.stopSpeakingNow()
                try {
                    s.recognizer?.cancel()
                } catch (e: Exception) {
                }
                s.busy = false
                s.setState("listening")
                s.listen(100)
            }
        }

        /** Screen se likha hua message bhejne ke liye. */
        fun submit(text: String) {
            val s = instance ?: return
            s.main.post { s.handleTyped(text) }
        }
    }

    private var pill: OverlayPill? = null
    private var live: LiveSession? = null
    private var liveOn = false

    /** Yaadein internet se milao (app reset ke baad bhi wapas aa jati hain). */
    private fun syncMemories() {
        val base = prefs().getString("url", MainActivity.DEFAULT_URL)!!.trim().trimEnd('/')
        val token = prefs().getString("token", "")!!
        Thread {
            val local = memories()
            val all = Net.memorySync(base, token, local) ?: return@Thread
            val merged = (all + local).distinct().takeLast(40)
            prefs().edit().putString("memories", merged.joinToString("\n")).apply()
        }.start()
    }

    private fun startLive() {
        if (liveOn) return
        if (prefs().getBoolean("netguard", false) && !online()) { Chat.add("noor", "Net nahi hai, Live nahi chalega."); return }
        liveOn = true
        liveActive = true
        liveLast = System.currentTimeMillis()
        main.postDelayed(idleCheck, 10000)
        stopSpeakingNow()
        busy = false
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        val base = prefs().getString("url", MainActivity.DEFAULT_URL)!!.trim().trimEnd('/')
        val token = prefs().getString("token", "")!!
        Jarvis.loud(this)
        Chat.add("noor", "Live shuru ho rahi hai... thodi der mein bolein.")
        setState("thinking")
        val ls = LiveSession(
            base, token, memories().joinToString("; "), prefs().getInt("gate", 900),
            { st -> main.post { liveLast = System.currentTimeMillis(); if (liveOn) setState(st) } },
            { who, t -> main.post { liveLast = System.currentTimeMillis(); Chat.add(who, t) } },
            { name, args, cb -> main.post { liveTool(name, args, cb) } },
            { msg -> main.post { endLive(msg) } }
        )
        live = ls
        ls.start()
    }

    @Volatile private var liveLast = 0L
    private val idleCheck = object : Runnable {
        override fun run() {
            if (!liveOn) return
            if (System.currentTimeMillis() - liveLast > 45000) live?.end("Koi awaaz nahi aayi, Live band. Hello ${Persona.title} bolein.")
            else main.postDelayed(this, 10000)
        }
    }

    private fun stopLive() {
        live?.end("Live band kar di.")
    }

    private fun endLive(msg: String) {
        liveOn = false
        liveActive = false
        live = null
        Chat.add("noor", msg)
        setState("listening")
        listen(500)
    }

    /** Live ke dauran Noor ke kaam (do_action). */
    private fun liveTool(name: String, args: JSONObject, cb: (String) -> Unit) {
        if (name == "web_search") {
            val base = prefs().getString("url", MainActivity.DEFAULT_URL)!!.trim().trimEnd('/')
            val token = prefs().getString("token", "")!!
            val q = args.optString("query")
            Thread {
                val r = Net.search(base, token, q)
                main.post { cb(if (r.isBlank()) "Internet se jawab nahi mila." else r) }
            }.start()
            return
        }
        if (name == "switch_persona") {
            val toRox = args.optString("name").lowercase().contains("rox")
            if (toRox == Persona.isRox) {
                cb("Wo pehle se hi baat kar raha hai.")
                return
            }
            cb("Theek hai, bula rahi hoon.")
            Persona.set(this, if (toRox) "rox" else "noor")
            main.postDelayed({
                live?.end(if (toRox) "Rox aa gaya." else "Noor aa gayi.")
                main.postDelayed({ startLive() }, 1500)
            }, 1800)
            return
        }
        val intent = args.optString("intent")
        val slots = try {
            JSONObject(args.optString("args_json", "{}").ifBlank { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }
        try {
            when (intent) {
                "screen_look" -> main.postDelayed({ cb(NoorAccess.readScreen().ifBlank { "Screen padh nahi saki (Accessibility ON karein)." }) }, 1200)
                "reminder" -> cb("Theek hai, " + addReminder(slots) + " par yaad dilaungi.")
                "prayer_times" -> Jarvis.prayerText(this) { cb(it) }
                "edit_image" -> {
                    if (lastPic.isBlank()) cb("Pehle gallery se photo bhejni hogi (Photo button).")
                    else {
                        makeImage(slots.optString("prompt"), lastPic)
                        cb("Photo edit ho rahi hai, Gallery mein aa jayegi.")
                    }
                }
                "hologram" -> {
                    makeHolo(slots.optString("subject"))
                    cb("Hologram ban raha hai, screen par aa jayega.")
                }
                "image" -> {
                    makeImage(slots.optString("prompt"))
                    cb("Tasveer ban rahi hai, Gallery mein aa jayegi.")
                }
                "copy" -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Noor", slots.optString("text")))
                    cb("Copy ho gaya.")
                }
                "remember" -> {
                    val t = slots.optString("text")
                    if (t.isNotBlank()) {
                        val all = (memories() + t.replace("\n", " ")).takeLast(20)
                        prefs().edit().putString("memories", all.joinToString("\n")).apply()
                    }
                    cb("Yaad rakh liya.")
                }
                "camera" -> {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            .putExtra(MainActivity.EXTRA_VISION, if (slots.optBoolean("on", true)) "on" else "off")
                    )
                    cb("Camera tab khol diya.")
                }
                else -> {
                    val fail = Actions.execute(this, intent, slots)
                    cb(fail ?: "ho gaya")
                }
            }
        } catch (e: Exception) {
            cb("Ye kaam nahi ho saka.")
        }
    }
    private var nextProactive = 0L
    private var proactiveCount = 0

    private fun scheduleProactive() {
        // 40 se 90 minute baad Noor khud baat karti hai
        nextProactive = System.currentTimeMillis() + (40 + (Math.random() * 50).toInt()) * 60000L
    }

    private val proactiveTick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val today = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())
                if (prefs().getBoolean("briefing", true) && hour in 8..11 && pm.isInteractive && !speaking && !busy && !liveOn &&
                    prefs().getString("lastBriefing", "") != today
                ) {
                    prefs().edit().putString("lastBriefing", today).apply()
                    activeUntil = System.currentTimeMillis() + 60000
                    ask("(BRIEFING) " + Persona.you + " ke liye aaj subah ki briefing do. Shehar: " + Jarvis.city(this@NoorService) + ", mausam bhi batao.")
                    main.postDelayed(this, 60000)
                    return
                }
                if (!speaking && !busy && !liveOn) {
                    Thread {
                        val pr = try { Jarvis.duePrayer(this@NoorService) } catch (e: Exception) { null }
                        val nd = if (pr == null) try { Jarvis.nudge(this@NoorService) } catch (e: Exception) { null } else null
                        main.post {
                            if (!speaking && !busy && !liveOn) {
                                if (pr != null) say(Persona.you + ", " + pr + " ki namaz ka waqt ho gaya hai.")
                                else if (nd != null) say(nd)
                            }
                        }
                    }.start()
                }
                val ok = prefs().getBoolean("proactive", true) && hour in 9..22 && pm.isInteractive &&
                    !speaking && !busy && !liveOn && System.currentTimeMillis() >= nextProactive
                if (ok) {
                    scheduleProactive()
                    proactiveCount++
                    if (proactiveCount % 2 == 1) speakTrending() else ask("(Noor khud baat shuru kar rahi hai.) Rizwan se apni marzi se koi chhoti, dilchasp baat ya ek sawal karo.")
                }
            } catch (e: Exception) {
            }
            main.postDelayed(this, 60000)
        }
    }

    // ---------- yaad dilana ----------
    private var activeReminder: String? = null
    private var reminderRepeats = 0
    private var nextRemind = 0L

    private fun loadReminders(): MutableList<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (line in (prefs().getString("reminders", "") ?: "").split("\n")) {
            val k = line.indexOf('|')
            if (k > 0) {
                val t = line.substring(0, k).toLongOrNull() ?: continue
                out.add(Pair(t, line.substring(k + 1)))
            }
        }
        return out
    }

    private fun saveReminders(l: List<Pair<Long, String>>) {
        prefs().edit().putString("reminders", l.joinToString("\n") { "${it.first}|${it.second.replace("\n", " ")}" }).apply()
    }

    private fun addReminder(slots: JSONObject): String {
        val text = slots.optString("text").ifBlank { "kuch zaroori kaam" }
        val cal = java.util.Calendar.getInstance()
        val minutes = slots.optInt("minutes", 0)
        val hour = slots.optInt("hour", -1)
        if (minutes > 0) {
            cal.add(java.util.Calendar.MINUTE, minutes)
        } else if (hour >= 0) {
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, slots.optInt("minute", 0))
            cal.set(java.util.Calendar.SECOND, 0)
            cal.add(java.util.Calendar.DAY_OF_YEAR, slots.optInt("day_offset", 0))
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        } else {
            cal.add(java.util.Calendar.MINUTE, 30)
        }
        val l = loadReminders()
        l.add(Pair(cal.timeInMillis, text))
        saveReminders(l)
        return java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(cal.timeInMillis))
    }

    private val reminderTick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val now = System.currentTimeMillis()
                if (activeReminder == null) {
                    val l = loadReminders()
                    val due = l.firstOrNull { it.first <= now }
                    if (due != null) {
                        l.remove(due)
                        saveReminders(l)
                        activeReminder = due.second
                        reminderRepeats = 0
                        nextRemind = 0L
                    }
                }
                val r = activeReminder
                if (r != null && !speaking && !busy && !liveOn && now >= nextRemind) {
                    if (reminderRepeats >= 15) {
                        activeReminder = null
                    } else {
                        reminderRepeats++
                        nextRemind = now + 60000
                        stopSpeakingNow()
                        say("${Persona.you}, yaad dilana tha: $r. Bataiye, ho gaya?")
                    }
                }
            } catch (e: Exception) {
            }
            main.postDelayed(this, 10000)
        }
    }

    private fun speakTrending() {
        busy = true
        setState("thinking")
        val base = prefs().getString("url", MainActivity.DEFAULT_URL)!!.trim().trimEnd('/')
        val token = prefs().getString("token", "")!!
        Thread {
            val r = Net.trending(base, token)
            main.post {
                busy = false
                if (r.isNotBlank()) say(r) else doneSpeaking()
            }
        }.start()
    }

    private fun setState(s: String) {
        Chat.set(s)
        pill?.update(s)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var speaking = false
    private var busy = false
    private var activeUntil = 0L
    private val history = ArrayList<Pair<String, String>>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null

    private val wakeRe = Regex("(hey|hay|hi|he|ae|ai|है|हे|हेय|ऐ|ہے|اے|ہی)?\\s*(noor|nur|nour|नूर|نور)")
    private val roxRe = Regex("(hey|hay|hi|he|ae|ai|है|हे|हेय|ऐ|ہے|اے|ہی)?\\s*(rox|roks|rocks|rocx|rokh|रॉक्स|रोक्स|रॉक्|راکس|روکس|راکز)")
    private val stopRe = Regex("\\bstop\\b|band karo|sunna band|bas karo|chup ho|chup kar|सुनना बंद|बंद करो|बस करो|चुप|بند کرو|سننا بند|بس کرو|چپ")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val n = buildNote("Noor sun rahi hai. \"Hey Noor\" bolein.")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        if (!running) {
            running = true
            isRunning = true
            instance = this
            Persona.load(this)
            syncMemories()
            pill = OverlayPill(this)
            pill?.show()
            setState("listening")
            scheduleProactive()
            main.postDelayed(proactiveTick, 60000)
            main.postDelayed(reminderTick, 10000)
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noor:listen")
                wake?.acquire(12L * 60 * 60 * 1000)
            } catch (e: Exception) {
            }
            tts = TextToSpeech(this) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            main.post { doneSpeaking() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            main.post { doneSpeaking() }
                        }
                    })
                }
            }
            listen(500)
        }
        if (intent?.action == ACTION_TEXT) {
            val typed = intent.getStringExtra(EXTRA_TEXT)
            if (!typed.isNullOrBlank()) handleTyped(typed)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        instance = null
        try {
            live?.end("")
        } catch (e: Exception) {
        }
        liveActive = false
        pill?.hide()
        pill = null
        Chat.set("off", "")
        main.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        try {
            tts?.shutdown()
        } catch (e: Exception) {
        }
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ---------- notification ----------
    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Noor", NotificationManager.IMPORTANCE_LOW))
    }

    @Suppress("DEPRECATION")
    private fun buildNote(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, NoorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Noor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Band karo", stop)
            .build()
    }

    private fun note(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNote(text))
        } catch (e: Exception) {
        }
    }

    // ---------- sunna ----------
    private fun listen(delayMs: Long = 0) {
        main.postDelayed({ startRecognizer() }, delayMs)
    }

    private fun startRecognizer() {
        if (!running || speaking || busy || liveOn) return
        if (prefs().getBoolean("ptt", false) && System.currentTimeMillis() > activeUntil) {
            setState("idle")
            note("Mic dabakar bolein.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            note("Phone mein speech service nahi mili (Google app chahiye).")
            return
        }
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        setState("listening")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    note("Mic ki ijazat chahiye. Noor app kholkar allow karein.")
                    return
                }
                val wait = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) 1500L else 300L
                listen(wait)
            }

            override fun onResults(results: android.os.Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) {
                    listen(200)
                    return
                }
                handleHeard(t)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayOf("ur-PK", "en-IN"))
            .putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 1100L)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        try {
            r.startListening(i)
        } catch (e: Exception) {
            listen(1500)
        }
    }

    /** Likha hua message: bolne ki tarah hi kaam hota hai aur jawab awaaz mein aata hai. */
    private fun handleTyped(text: String) {
        activeUntil = System.currentTimeMillis() + 60000
        if (busy) {
            Chat.add("noor", "Ek second, pehla kaam ho raha hai.")
            return
        }
        stopSpeakingNow()
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        Chat.add("me", text)
        ask(text)
    }

    private fun stopSpeakingNow() {
        speakGen++
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        speaking = false
    }

    private fun handleHeard(raw: String) {
        val lower = raw.lowercase().trim()
        var text = raw.trim()
        val now = System.currentTimeMillis()
        val mRox = roxRe.find(lower)
        val mNoor = wakeRe.find(lower)
        if (mRox != null || mNoor != null) {
            activeUntil = now + 60000
            val wantRox = mRox != null && (mNoor == null || mRox.range.first <= mNoor.range.first)
            val changed = wantRox != Persona.isRox
            if (changed) {
                Persona.set(this, if (wantRox) "rox" else "noor")
                pill?.update("listening")
                Chat.set("listening")
            }
            text = lower.replace(roxRe, "").replace(wakeRe, "").trim()
            val bare = text.replace(Regex("(hello|hallo|hey|hi|hlo|helo|ok|okay|ae|aye|are|oye|ji|sun|suno|\\s|,|\\.)"), "")
            if (bare.isEmpty() && !liveOn && Regex("hello|hallo|hey|\\bhi\\b|hlo|helo|हेलो|हैलो|हे ").containsMatchIn(lower)) {
                startLive()
                return
            }
            if (changed && text.isEmpty()) {
                say(if (wantRox) "Ji Sir, Rox hazir hai. Hukm kijiye." else "Ji Rizwan, main Noor hoon. Boliye.")
                return
            }
        }
        if (now > activeUntil) {
            listen(100)
            return
        }
        if (stopRe.containsMatchIn(lower)) {
            activeUntil = 0
            say("Theek hai, jab chahein Hey ${Persona.title} bolein.")
            return
        }
        if (text.isEmpty()) {
            say(if (Persona.isRox) "Ji Sir, boliye." else "Ji, boliye.")
            return
        }
        Chat.add("me", text)
        ask(text)
    }

    // ---------- server se jawab ----------
    private fun prefs() = getSharedPreferences("noor", Context.MODE_PRIVATE)

    private fun memories(): List<String> =
        prefs().getString("memories", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    private fun context(): String {
        val time = SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
        var s = "Local time: $time."
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            s += " Battery: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%."
        } catch (e: Exception) {
        }
        try {
            if (appsCache == null) {
                val pm = packageManager
                val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                appsCache = pm.queryIntentActivities(main, 0)
                    .map { it.loadLabel(pm).toString() }
                    .filter { it.isNotBlank() && it != "Noor" }
                    .distinct().sorted().joinToString(", ").take(1500)
            }
            s += " Installed apps: $appsCache."
        } catch (e: Exception) {
        }
        return s
    }

    private var appsCache: String? = null

    @Volatile
    var lastPic = ""

    private fun makeHolo(subject: String) {
        if (subject.isNotBlank()) try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra(MainActivity.EXTRA_VISION, "holo")) } catch (e: Exception) {}
        if (subject.isBlank() || subject.lowercase() in listOf("clear", "none", "off", "band")) {
            Hud.put(null)
            return
        }
        val base = prefs().getString("url", MainActivity.DEFAULT_URL)!!.trim().trimEnd('/')
        val token = prefs().getString("token", "")!!
        Hud.busy = true
        Hud.ping()
        Thread {
            val bytes = Net.holo(base, token, subject)
            val bmp = if (bytes != null && bytes.size > 1000) android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            main.post {
                Hud.busy = false
                if (bmp == null) {
                    Chat.add("noor", "Hologram nahi ban saka, dobara bolein.")
                    Hud.ping()
                } else {
                    Hud.put(bmp)
                    try {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra(MainActivity.EXTRA_VISION, "holo"))
                    } catch (e: Exception) {
                    }
                }
            }
        }.start()
    }

    private fun makeImage(prompt: String, src: String = "") {
        val base = prefs().getString("url", MainActivity.DEFAULT_URL)!!.trim().trimEnd('/')
        val token = prefs().getString("token", "")!!
        Thread {
            val bytes = Net.image(base, token, prompt, src)
            main.post {
                if (bytes == null || bytes.size < 1000) {
                    Chat.add("noor", "Tasveer nahi ban saki, thodi der baad dobara bolein.")
                    return@post
                }
                try {
                    val name = "Noor_" + System.currentTimeMillis() + ".jpg"
                    val cv = android.content.ContentValues()
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Noor")
                    }
                    val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        contentResolver.openOutputStream(uri)?.use { o ->
                            if (bmp != null) bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, o) else o.write(bytes)
                        }
                        Chat.add("noor", "Tasveer ban gayi, Gallery mein Pictures/Noor mein hai.")
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        } catch (e: Exception) {
                        }
                    } else {
                        Chat.add("noor", "Tasveer save nahi ho saki.")
                    }
                } catch (e: Exception) {
                    Chat.add("noor", "Tasveer save nahi ho saki.")
                }
            }
        }.start()
    }

    private var lookDepth = 0
    private var pendingGoal = ""
    private var pendingAt = 0L

    /** Screen padhkar agla kadam LLM se poochho (YouTube search, subscribe, follow waghera). */
    private fun scheduleLook(goal: String, delay: Long) {
        if (lookDepth >= 40) {
            lookDepth = 0
            pendingGoal = ""
            say("Itne kadam ho gaye Rizwan, kaam poora nahi hua. Batao to main wahin se aage badhoon?")
            return
        }
        lookDepth++
        if (goal.isNotBlank()) {
            pendingGoal = goal
            pendingAt = System.currentTimeMillis()
        }
        busy = true
        setState("thinking")
        main.postDelayed({
            val scr = NoorAccess.readScreen()
            busy = false
            if (scr.isBlank()) {
                lookDepth = 0
                say("Screen padh nahi saki. Settings mein Accessibility mein Noor ON karein.")
            } else {
                ask("(SCREEN DEKHA) Maqsad: $goal. Screen par ye likha hai ([ ] mein wale dabne wale hain): $scr. Agla sirf ek kadam batao: intent screen (action tap_text / tap / type / enter / scroll_down / scroll_up / back) ke saath look true aur goal wahi, reply khali. Kabhi manual karne ko mat kaho, khud karo. Agar kuch zaroori jaankari chahiye (naya naam, kaun si photo, haan/na) to Rizwan se ek chhota sawal poocho (intent none, slots done false). Paise, delete, post, kisi ko message ya account login se pehle sawal poocho. Maqsad poora ho jaye to intent none, slots done true aur ek chhota jawab.")
            }
        }, delay)
    }

    private fun online(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun ask(textIn: String, pic: String = "") {
        if (prefs().getBoolean("netguard", false) && !online()) {
            Chat.add("noor", "Net nahi hai, isliye Noor so rahi hai.")
            return
        }
        var text = textIn
        if (!text.startsWith("(SCREEN")) {
            lookDepth = 0
            if (pendingGoal.isNotBlank() && System.currentTimeMillis() - pendingAt < 300000 && !text.startsWith("(Noor khud")) {
                text = "(Adhoora kaam: $pendingGoal. Ye uska jawab ya naya hukm hai, agar jawab hai to kaam aage badhao: screen intent look true ke saath) " + text
            }
        }
        val rem = activeReminder
        if (rem != null && !text.startsWith("(Noor khud")) {
            activeReminder = null
            text = "(Noor ne abhi yaad dilaya tha: $rem.) $text"
        }
        busy = true
        setState("thinking")
        note("Soch rahi hoon...")
        val base = prefs().getString("url", MainActivity.DEFAULT_URL)!!.trim().trimEnd('/')
        val token = prefs().getString("token", "")!!
        val hist = ArrayList(history)
        val mem = memories()
        val ctx = context()
        Thread {
            val res = try {
                var img = ""
            if (pic.isNotBlank()) {
                img = pic
            } else if (MainActivity.resumed && !text.startsWith("(SCREEN")) {
                val latch = java.util.concurrent.CountDownLatch(1)
                MainActivity.requestFrame {
                    img = it
                    latch.countDown()
                }
                latch.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            Net.chat(base, token, text, hist, mem, ctx, img)
            } catch (e: Exception) {
                JSONObject().put("reply", "Internet check karein, server se connection nahi ho paya.")
                    .put("intent", "none").put("slots", JSONObject())
            }
            main.post {
                busy = false
                onResult(text, res)
            }
        }.start()
    }

    private fun onResult(userText: String, j: JSONObject) {
        val reply = j.optString("reply")
        val intent = j.optString("intent", "none")
        val slots = j.optJSONObject("slots") ?: JSONObject()
        if (intent == "none" && slots.optBoolean("done", !reply.trim().endsWith("?"))) pendingGoal = ""
        history.add(Pair("user", userText))
        history.add(Pair("assistant", reply))
        while (history.size > 12) history.removeAt(0)

        when (intent) {
            "none", "search", "weather", "" -> say(reply)
            "remember" -> {
                val t = slots.optString("text")
                if (t.isNotBlank()) {
                    val all = (memories() + t.replace("\n", " ")).takeLast(20)
                    prefs().edit().putString("memories", all.joinToString("\n")).apply()
                    say("Theek hai, yaad rakh liya.")
                } else {
                    say(reply)
                }
            }
            "camera" -> {
                val on = slots.optBoolean("on", true)
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            .putExtra(MainActivity.EXTRA_VISION, if (on) "on" else "off")
                    )
                    say(if (reply.isNotBlank()) reply else if (on) "Camera on kar diya Rizwan. Noor app khuli rakhna, ab poochho kya dikh raha hai." else "Camera band kar diya.")
                } catch (e: Exception) {
                    say("Camera kholne ke liye Settings mein button 2 se 'dusri app ke upar dikhna' allow karein.")
                }
            }
            "copy" -> {
                val t = slots.optString("text")
                if (t.isNotBlank()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Noor", t))
                    Chat.add("noor", t)
                }
                say(if (reply.isNotBlank()) reply else "Copy kar diya Rizwan, ab kahin bhi paste kar sakte hain.")
            }
            "hologram" -> {
                say(if (reply.isNotBlank()) reply else "Theek hai, hologram bana rahi hoon.")
                makeHolo(slots.optString("subject").ifBlank { userText })
            }
            "image" -> {
                val pr = slots.optString("prompt").ifBlank { userText }
                say(if (reply.isNotBlank()) reply else "Theek hai, tasveer bana rahi hoon.")
                makeImage(pr)
            }
            "edit_image" -> {
                if (lastPic.isBlank()) say("Pehle 🖼 Photo se gallery ki tasveer bhejein, phir bolein kya badalna hai.")
                else {
                    say(if (reply.isNotBlank()) reply else "Theek hai, photo edit kar rahi hoon.")
                    makeImage(slots.optString("prompt").ifBlank { userText }, lastPic)
                }
            }
            "prayer_times" -> Jarvis.prayerText(this) { say(it) }
            "screen_look" -> scheduleLook(slots.optString("goal"), 800)
            "reminder" -> {
                val whenTxt = addReminder(slots)
                say(if (reply.isNotBlank()) reply else "Theek hai Rizwan, $whenTxt par yaad dila dungi.")
            }
            else -> {
                val fail = Actions.execute(this, intent, slots)
                if (fail == null && slots.optBoolean("look", false)) {
                    if (reply.isNotBlank()) Chat.add("noor", reply)
                    busy = false
                    scheduleLook(slots.optString("goal"), if (intent == "open_app") 3500 else 1500)
                    return
                }
                if (fail != null && intent == "open_app" && fail.contains("nahi mili")) {
                    val nm = slots.optString("name")
                    ask("\"$nm\" naam ki app mere phone mein nahi mili. Net par dhoondhkar batao ye kya hai.")
                } else {
                    say(fail ?: reply)
                }
            }
        }
    }

    // ---------- bolna ----------
    private var speakGen = 0

    private fun say(text: String) {
        if (text.isBlank()) {
            doneSpeaking()
            return
        }
        Chat.add("noor", text)
        setState("speaking")
        speaking = true
        val g = ++speakGen
        main.postDelayed({ if (speaking && g == speakGen) doneSpeaking() }, 40000)
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        note(text)
        val base = prefs().getString("url", MainActivity.DEFAULT_URL)!!.trim().trimEnd('/')
        val token = prefs().getString("token", "")!!
        Thread {
            val f = try {
                Net.tts(base, token, text, cacheDir)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (!running) return@post
                if (f != null) playFile(f, text) else speakLocal(text)
            }
        }.start()
    }

    private fun playFile(f: java.io.File, text: String) {
        try {
            player?.release()
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener {
                it.release()
                player = null
                doneSpeaking()
            }
            p.setOnErrorListener { mp, _, _ ->
                mp.release()
                player = null
                speakLocal(text)
                true
            }
            p.prepare()
            Jarvis.loud(this)
            p.setVolume(1f, 1f)
            p.start()
            player = p
        } catch (e: Exception) {
            speakLocal(text)
        }
    }

    private fun speakLocal(text: String) {
        val t = tts
        if (!ttsReady || t == null) {
            doneSpeaking()
            return
        }
        val arabic = text.any { it in '؀'..'ۿ' }
        t.setLanguage(if (arabic) Locale("ur", "PK") else Locale("hi", "IN"))
        t.setPitch(if (Persona.isRox) 0.7f else 1.1f)
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "noor") != TextToSpeech.SUCCESS) doneSpeaking()
    }

    private fun doneSpeaking() {
        speaking = false
        activeUntil = maxOf(activeUntil, System.currentTimeMillis() + 60000)
        note("Noor sun rahi hai. \"Hey Noor\" bolein.")
        setState("listening")
        listen(200)
    }
}
