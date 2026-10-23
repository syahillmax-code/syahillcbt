package com.absm.examonlan

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var topExamBar: LinearLayout
    private lateinit var txtBatteryLevel: TextView
    private lateinit var txtExamTimer: TextView
    private lateinit var txtMotivationalQuote: TextView

    private var examTimer: CountDownTimer? = null
    private var batteryReceiver: BroadcastReceiver? = null
    
    private val quoteHandler = Handler(Looper.getMainLooper())
    private var quoteIndex = 0

    // 12 Variasi Kalimat Motivasi & Larangan Nyontek
    private val motivationalQuotes = arrayOf(
        "🛡️ Mr. Syahill is watching you!!",
        "🚫 Jangan nyontek ya! Prestasi jujur itu berkah!",
        "✨ Semangat ujiannya, kerjakan dengan teliti!",
        "👀 Ingat, Allah Maha Melihat & Mr. Syahill mengawasi!",
        "💡 Jujur itu juara, percaya pada kemampuan sendiri!",
        "⚠️ Dilarang lirik kanan-kiri, tetap fokus ke layarmu!",
        "🌸 Bismillah, tenang dan raih hasil terbaik!",
        "🎯 Kejujuran mencerminkan karakter santri hebat!",
        "📚 Teliti sebelum submit jawabanmu ya!",
        "💪 Usaha sendiri rasanya jauh lebih membanggakan!",
        "🧠 Baca soal perlahan, pasti bisa terjawab!",
        "🌟 Santriwati ABSM: Unggul, Mandiri, dan Jujur!"
    )

    // Palet Warna-Warni Menarik yang Menyala di Latar Gelap
    private val quoteColors = arrayOf(
        Color.parseColor("#38bdf8"), // Sky Blue
        Color.parseColor("#f87171"), // Coral Red
        Color.parseColor("#4ade80"), // Mint Green
        Color.parseColor("#facc15"), // Bright Yellow
        Color.parseColor("#fb923c"), // Warm Orange
        Color.parseColor("#f472b6"), // Soft Pink
        Color.parseColor("#c084fc"), // Lavender Purple
        Color.parseColor("#2dd4bf"), // Aqua Teal
        Color.parseColor("#fbbf24"), // Amber Gold
        Color.parseColor("#34d399"), // Emerald
        Color.parseColor("#60a5fa"), // Neon Blue
        Color.parseColor("#e879f9")  // Light Fuchsia
    )

    private val quoteRunnable = object : Runnable {
        override fun run() {
            if (isInExamSession) {
                quoteIndex = (quoteIndex + 1) % motivationalQuotes.size
                txtMotivationalQuote.text = motivationalQuotes[quoteIndex]
                txtMotivationalQuote.setTextColor(quoteColors[quoteIndex % quoteColors.size])
                quoteHandler.postDelayed(this, 3 * 60 * 1000L) // Berganti setiap 3 menit
            }
        }
    }

    private var currentExitPin = "12345"
    private var isInExamSession = false
    private val CAMERA_PERMISSION_CODE = 101

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        checkCameraPermission()
        enforceMinVolume()
        enableImmersiveFullscreen()
        try { startLockTask() } catch (e: Exception) {}

        topExamBar = findViewById(R.id.topExamBar)
        webView = findViewById(R.id.examWebView)
        txtBatteryLevel = findViewById(R.id.txtBatteryLevel)
        txtExamTimer = findViewById(R.id.txtExamTimer)
        txtMotivationalQuote = findViewById(R.id.txtMotivationalQuote)

        val ws: WebSettings = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean { 
                return false 
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null && url.contains("android_asset/index.html")) {
                    topExamBar.visibility = View.GONE
                    stopExamFeatures()
                }
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                if (!isInExamSession) {
                    result?.confirm()
                    return true
                }
                return super.onJsBeforeUnload(view, url, message, result)
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        findViewById<Button>(R.id.btnBackHome).setOnClickListener { showExitExamDialog() }
        findViewById<Button>(R.id.btnRefreshExam).setOnClickListener { 
            webView.reload()
            Toast.makeText(this, "Memuat ulang halaman...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startExamFeatures(durationMinutes: Int) {
        quoteIndex = 0
        txtMotivationalQuote.text = motivationalQuotes[0]
        txtMotivationalQuote.setTextColor(quoteColors[0])
        quoteHandler.removeCallbacks(quoteRunnable)
        quoteHandler.postDelayed(quoteRunnable, 3 * 60 * 1000L)

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    val batteryPct = (level * 100) / scale
                    txtBatteryLevel.text = "🔋 $batteryPct%"
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        examTimer?.cancel()
        val totalMillis = durationMinutes * 60 * 1000L
        examTimer = object : CountDownTimer(totalMillis, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val hours = (millisUntilFinished / 1000) / 3600
                val minutes = ((millisUntilFinished / 1000) % 3600) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                txtExamTimer.text = String.format("⏳ %02d:%02d:%02d", hours, minutes, seconds)
            }

            override fun onFinish() {
                txtExamTimer.text = "⏳ WAKTU HABIS"
                triggerViolationAlarm("Waktu ujian telah berakhir!")
            }
        }.start()
    }

    private fun stopExamFeatures() {
        quoteHandler.removeCallbacks(quoteRunnable)
        examTimer?.cancel()
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {}
            batteryReceiver = null
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    private fun enforceMinVolume() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val targetVolume = (maxVolume * 0.5).toInt()
            if (currentVolume < targetVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            }
        } catch (e: Exception) {}
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun startExamUrl(url: String, exitPin: String, durationMinutes: Int) {
            runOnUiThread {
                currentExitPin = exitPin
                isInExamSession = true
                topExamBar.visibility = View.VISIBLE
                enforceMinVolume()
                startExamFeatures(if (durationMinutes > 0) durationMinutes else 70)
                try { startLockTask() } catch (e: Exception) {}
                webView.loadUrl(url)
            }
        }

        @JavascriptInterface
        fun startExamUrl(url: String, exitPin: String) {
            startExamUrl(url, exitPin, 70)
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                isInExamSession = false
                topExamBar.visibility = View.VISIBLE
                webView.loadUrl(url)
            }
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread {
                stopExamFeatures()
                try { stopLockTask() } catch (e: Exception) {}
                finishAffinity()
            }
        }
    }

    private fun showExitExamDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "PIN Keluar"
        AlertDialog.Builder(this)
            .setTitle("🔒 Keluar Ujian")
            .setMessage("Masukkan PIN Keluar:")
            .setView(input)
            .setPositiveButton("Keluar") { _, _ ->
                if (input.text.toString().trim() == currentExitPin || input.text.toString().trim() == "12345") {
                    isInExamSession = false
                    stopExamFeatures()
                    webView.clearHistory()
                    webView.loadUrl("file:///android_asset/index.html")
                } else { 
                    triggerViolationAlarm("PIN Salah!") 
                }
            }
            .setNegativeButton("Batal") { d, _ -> 
                d.dismiss()
                enableImmersiveFullscreen() 
            }
            .show()
    }

    private fun triggerViolationAlarm(reason: String) {
        enforceMinVolume()
        try { 
            ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000) 
        } catch (e: Exception) {}
        Toast.makeText(this, "⚠️ $reason", Toast.LENGTH_LONG).show()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isInExamSession) { triggerViolationAlarm("Dilarang keluar dari layar ujian!") }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { 
            enableImmersiveFullscreen()
            enforceMinVolume() 
        } else if (isInExamSession) { 
            triggerViolationAlarm("Dilarang membuka notifikasi/aplikasi lain!") 
        }
    }

    private fun enableImmersiveFullscreen() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { 
            Toast.makeText(this, "Tombol Kembali dikunci.", Toast.LENGTH_SHORT).show()
            return true 
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopExamFeatures()
    }
}
