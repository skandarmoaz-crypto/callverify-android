// ============================================================
// ⚠️ تحذير | WARNING
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.switchmaterial.SwitchMaterial

const val DEFAULT_BACKEND_URL   = "https://listen-to-me--emoazvjd8.replit.app"
const val DEFAULT_APP_SECRET    = "callverify-app-secret-2024"
const val PREF_AUTO_REJECT      = "auto_reject"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var autoRejectSwitch: SwitchMaterial
    private lateinit var statusText: TextView

    private val callDetectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Handler(Looper.getMainLooper()).postDelayed({ webView.reload() }, 1_500L)
        }
    }

    private val periodicRefresher = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                webView.reload()
                handler.postDelayed(this, 60_000L)
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("callverify", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("backend_url", DEFAULT_BACKEND_URL)
            .putString("api_key",     DEFAULT_APP_SECRET)
            .apply()

        // ─── إعداد Toggle الرفض التلقائي ──────────────────────────────────────
        autoRejectSwitch = findViewById(R.id.autoRejectSwitch)
        statusText       = findViewById(R.id.autoRejectStatus)

        // استعادة الحالة المحفوظة | Restore saved state
        autoRejectSwitch.isChecked = prefs.getBoolean(PREF_AUTO_REJECT, false)
        updateStatusText(autoRejectSwitch.isChecked)

        autoRejectSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_AUTO_REJECT, isChecked).apply()
            updateStatusText(isChecked)
        }

        // ─── إعداد الـ WebView ─────────────────────────────────────────────────
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            loadWithOverviewMode = true
            useWideViewPort      = true
            databaseEnabled      = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.startsWith(DEFAULT_BACKEND_URL)) false
                else { startActivity(Intent(Intent.ACTION_VIEW, request.url)); true }
            }
        }
        webView.loadUrl(DEFAULT_BACKEND_URL)

        // ─── تشغيل خدمة المراقبة ───────────────────────────────────────────────
        try {
            val svc = Intent(this, CallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        } catch (e: Exception) { e.printStackTrace() }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            1001
        )

        requestIgnoreBatteryOptimizations()
        requestDefaultDialerRole()
    }

    private fun updateStatusText(autoRejectOn: Boolean) {
        statusText.text = if (autoRejectOn)
            "🔴 الوضع الحالي: رفض تلقائي فعّال — يتطلب تطبيق الهاتف الافتراضي"
        else
            "⚪ الوضع الحالي: مراقبة فقط"
    }

    override fun onResume() {
        super.onResume()
        webView.reload()
        val filter = IntentFilter(ACTION_CALL_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(callDetectedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(callDetectedReceiver, filter)
        handler.postDelayed(periodicRefresher, 60_000L)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(callDetectedReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(periodicRefresher)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun requestDefaultDialerRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER))
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQUEST_DEFAULT_DIALER)
            } else {
                val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (tm.defaultDialerPackage != packageName)
                    startActivityForResult(
                        Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                        }, REQUEST_DEFAULT_DIALER
                    )
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEFAULT_DIALER) webView.reload()
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName))
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
        } catch (e: Exception) { e.printStackTrace() }
    }

    companion object {
        private const val REQUEST_DEFAULT_DIALER = 2002
    }
}
