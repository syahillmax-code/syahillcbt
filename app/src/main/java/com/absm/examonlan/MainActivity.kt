package com.absm.examonlan

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Bundle
import android.os.CountDownTimer
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
    private var examTimer: CountDownTimer? = null
    private var batteryReceiver: BroadcastReceiver? = null

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

    private fun startExamFeatures() {
        // Monitor Persentase Baterai
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

        // Timer Hitung Mundur Ujian (Default: 90 Menit)
        examTimer?.cancel()
        examTimer = object : CountDownTimer(90 * 60 * 1000L, 1000L) {
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
        fun startExamUrl(url: String, exitPin: String) {
            runOnUiThread {
                currentExitPin = exitPin
                isInExamSession = true
                topExamBar.visibility = View.VISIBLE
                enforceMinVolume()
                startExamFeatures()
                try { startLockTask() } catch (e: Exception) {}
                webView.loadUrl(url)
            }
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
