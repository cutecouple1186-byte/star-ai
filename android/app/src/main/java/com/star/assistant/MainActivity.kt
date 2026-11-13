package com.star.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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

    private fun startListening(lang: String) {
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
        }
        muteBeep()
        r.startListening(i)
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
