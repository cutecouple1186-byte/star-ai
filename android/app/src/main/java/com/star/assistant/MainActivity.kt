package com.star.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * STAR: ek hi screen. Design web page (server) se aata hai.
 * Native hissa: mic se sunna, tasveer/video chunna, LIVE ke waqt screen on rakhna.
 */
class MainActivity : Activity() {

    companion object {
        // Vercel par deploy kiya hua Star server
        const val STAR_URL = "https://star-ai-virid.vercel.app"
        private const val REQ_MIC = 71
        private const val REQ_FILE = 72
    }

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var pendingLang: String? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val ui = Handler(Looper.getMainLooper())
    private var savedVol = -1

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = filePathCallback
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    startActivityForResult(Intent.createChooser(pick, "Tasveer ya video chunein"), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "StarNative")
        web.loadUrl(STAR_URL)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun listen(lang: String) {
            runOnUiThread { startListening(lang) }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                recognizer?.cancel()
                unmuteBeep()
            }
        }

        // Dabake rakhne par hi sunna (background nahi sunta); chhodne par finish()
        @JavascriptInterface
        fun listenHold(lang: String) {
            runOnUiThread { startListening(lang, true) }
        }

        @JavascriptInterface
        fun finish() {
            runOnUiThread { recognizer?.stopListening() }
        }

        // Phone mein jo apps hain unke naam (JSON list)
        @JavascriptInterface
        fun apps(): String {
            return try {
                val pm = packageManager
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val names = pm.queryIntentActivities(i, 0)
                    .map { it.loadLabel(pm).toString() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                JSONArray(names).toString()
            } catch (e: Exception) {
                "[]"
            }
        }

        // Sirf tab chalta hai jab Sir ne "haan" kaha. Natija JS ko onStarDevice(ok, msg) se milta hai.
        @JavascriptInterface
        fun device(action: String, target: String) {
            runOnUiThread { runDevice(action, target) }
        }

        // LIVE chalu hone par screen on rakho, warna Android mic band kar deta hai
        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun js(code: String) {
        web.evaluateJavascript(code, null)
    }

    // Sunne ki "beep" kam karne ke liye sunte waqt music volume 0, baad mein wapas.
    private fun muteBeep() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (savedVol < 0) savedVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {
        }
    }

    private fun unmuteBeep() {
        try {
            if (savedVol >= 0) {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
            }
        } catch (e: Exception) {
        }
        savedVol = -1
    }

    private fun startListening(lang: String, hold: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            js("window.onStarListenEnd && window.onStarListenEnd(true)")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.onStarListenEnd && window.onStarListenEnd(true)")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                ui.postDelayed({ unmuteBeep() }, 250)
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    js("window.onStarListenEnd && window.onStarListenEnd(false)")
                } else {
                    js("window.onStarHeard && window.onStarHeard(" + JSONObject.quote(text) + ")")
                }
            }

            override fun onError(error: Int) {
                ui.postDelayed({ unmuteBeep() }, 250)
                // 6 = chup raha, 7 = samajh nahi aaya: ye normal hain, galti nahi
                val quiet = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
                js("window.onStarListenEnd && window.onStarListenEnd(" + (!quiet) + ")")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            if (hold) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20000L)
            }
        }
        muteBeep()
        r.startListening(i)
    }

    private fun deviceResult(ok: Boolean, msg: String) {
        js("window.onStarDevice && window.onStarDevice(" + ok + "," + JSONObject.quote(msg) + ")")
    }

    private fun norm(t: String) = t.lowercase().replace(Regex("[^a-z0-9\\u0600-\\u06ff ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun runDevice(action: String, target: String) {
        try {
            when (action) {
                "open_app" -> openApp(target)
                "open_settings" -> openSettingsPage(target)
                "volume" -> changeVolume(target)
                "flashlight" -> flashlight(target)
                else -> deviceResult(false, "Ye kaam abhi mujhe aata nahi.")
            }
        } catch (e: Exception) {
            deviceResult(false, "Ye nahi ho saka: " + (e.message ?: "koi masla"))
        }
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val q = norm(name)
        if (q.isEmpty()) { deviceResult(false, "App ka naam samajh nahi aaya."); return }
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: String? = null
        var bestScore = 0
        for (ri in list) {
            val label = norm(ri.loadLabel(pm).toString())
            val score = when {
                label == q -> 4
                label.startsWith(q) -> 3
                label.contains(q) -> 2
                q.contains(label) && label.length >= 3 -> 1
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = ri.activityInfo.packageName }
        }
        val pkg = best
        val launch = if (pkg != null) pm.getLaunchIntentForPackage(pkg) else null
        if (launch == null) {
            deviceResult(false, "Sir, \"" + name + "\" naam ki app nahi mili.")
        } else {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            deviceResult(true, name + " khul gayi.")
        }
    }

    private fun openSettingsPage(target: String) {
        val t = target.lowercase()
        val action: String = when {
            (t == "wifi" || t == "internet" || t == "data") && Build.VERSION.SDK_INT >= 29 -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            t == "wifi" || t == "internet" -> Settings.ACTION_WIFI_SETTINGS
            t == "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            t == "display" -> Settings.ACTION_DISPLAY_SETTINGS
            t == "sound" -> Settings.ACTION_SOUND_SETTINGS
            t == "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            t == "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            t == "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            t == "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            t == "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            t == "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            t == "security" -> Settings.ACTION_SECURITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        deviceResult(true, "Settings khol di. Wahan se aap khud on/off kar sakte hain.")
    }

    private fun changeVolume(how: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        unmuteBeep()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = Math.max(1, max / 5)
        val target = when (how.lowercase()) {
            "up" -> Math.min(max, cur + step)
            "down" -> Math.max(0, cur - step)
            "max" -> max
            "mute" -> 0
            else -> -1
        }
        if (target < 0) { deviceResult(false, "Awaaz ka tareeqa samajh nahi aaya."); return }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        deviceResult(true, "Awaaz " + target + "/" + max + " kar di.")
    }

    private fun flashlight(state: String) {
        val on = state.lowercase() == "on"
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) { deviceResult(false, "Is phone mein torch nahi mili."); return }
        cm.setTorchMode(id, on)
        deviceResult(true, if (on) "Torch on kar di." else "Torch band kar di.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingLang?.let { startListening(it) }
            pendingLang = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            var result: Array<Uri>? = null
            if (resultCode == RESULT_OK && data != null) {
                val list = ArrayList<Uri>()
                data.data?.let { list.add(it) }
                val clip = data.clipData
                if (clip != null) {
                    for (k in 0 until clip.itemCount) list.add(clip.getItemAt(k).uri)
                }
                result = list.toTypedArray()
            }
            fileCb?.onReceiveValue(result)
            fileCb = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onPause() {
        unmuteBeep()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        unmuteBeep()
        recognizer?.destroy()
        web.destroy()
        super.onDestroy()
    }
}
