package com.example.exambrowser

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.net.Uri

class MainActivity : AppCompatActivity() {

    companion object {
        // ====== UBAH SESUAI KEBUTUHAN ======
        const val EXAM_URL = "https://uol.smkwahapo.sch.id/"
        const val RELEASE_DEVICE_OWNER_ON_EXIT = false // true = lepas status device owner saat keluar
        // ===================================
    }

    private lateinit var webView: WebView
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val allowedHost: String? = Uri.parse(EXAM_URL).host

    // Pemantau status pin: bila pin pernah aktif lalu dilepas -> aplikasi otomatis keluar
    private val handler = Handler(Looper.getMainLooper())
    private var lockConfirmed = false
    private var exiting = false

    private fun lockState(): Int =
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState

    private val lockWatcher = object : Runnable {
        override fun run() {
            if (exiting) return
            if (lockState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                lockConfirmed = true
            } else if (lockConfirmed) {
                exitExam()
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cegah screenshot/rekam layar & jaga layar tetap menyala
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, AdminReceiver::class.java)

        webView = findViewById(R.id.webView)
        setupWebView()
        if (savedInstanceState == null) webView.loadUrl(EXAM_URL) else webView.restoreState(savedInstanceState)

        handler.postDelayed(lockWatcher, 500)

        findViewById<Button>(R.id.btnExit).setOnClickListener { confirmExit() }

        // Tombol Back dinonaktifkan (ganti ke webView.goBack() bila diperlukan)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* diblokir */ }
        })
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Penanda agar server CI3 bisa mewajibkan akses dari aplikasi ini
            userAgentString = userAgentString + " ExamBrowserApp/1.0"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Hanya izinkan domain ujian
                return request.url.host != allowedHost
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (exiting) return
        // Pin sudah pernah aktif tetapi kini terlepas -> keluar, jangan dipasang ulang
        if (lockConfirmed && lockState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            exitExam()
            return
        }
        enterLockMode()
    }

    private fun enterLockMode() {
        if (dpm.isDeviceOwnerApp(packageName)) {
            // Device owner: pinning penuh TANPA dialog peringatan
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        }
        if (lockState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            try { startLockTask() } catch (_: Exception) { }
        }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Keluar dari ujian")
            .setMessage("Yakin ingin keluar dari aplikasi?")
            .setPositiveButton("Keluar") { _, _ -> exitExam() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun exitExam() {
        if (exiting) return
        exiting = true
        handler.removeCallbacksAndMessages(null)
        try { stopLockTask() } catch (_: Exception) { }
        if (RELEASE_DEVICE_OWNER_ON_EXIT && dpm.isDeviceOwnerApp(packageName)) {
            dpm.clearDeviceOwnerApp(packageName)
        }
        finishAndRemoveTask()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
