package com.star.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity
import org.json.JSONObject

/**
 * STAR: ek hi screen. Design web page (server) se aata hai, mic aur sunna yahan native hai.
 */
class MainActivity : Activity() {

    companion object {
        // Vercel par deploy kiya hua Star server
        const val STAR_URL = "https://star-ai-virid.vercel.app"
        private const val REQ_MIC = 71
    }

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var pendingLang: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
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
            runOnUiThread { recognizer?.stopListening() }
        }
    }

    private fun js(code: String) {
        web.evaluateJavascript(code, null)
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
            override fun onResults(results: android.os.Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    js("window.onStarListenEnd && window.onStarListenEnd(true)")
                } else {
                    js("window.onStarHeard && window.onStarHeard(" + JSONObject.quote(text) + ")")
                }
            }

            override fun onError(error: Int) {
                js("window.onStarListenEnd && window.onStarListenEnd(true)")
            }

            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
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
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        web.destroy()
        super.onDestroy()
    }
}
