// ============================================================
// ⚠️ تحذير | WARNING
// ------------------------------------------------------------
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
//    رابط الاستضافة مثبّت داخلياً في الكود ولا يظهر للمستخدم إطلاقاً.
//
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
//    The backend URL is hardcoded internally and never shown to the user.
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

const val DEFAULT_BACKEND_URL = "https://listen-to-me--emoazvjd8.replit.app"
const val DEFAULT_APP_SECRET  = "callverify-app-secret-2024"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // مستقبل يُجدّد الـ WebView فور وصول إشعار "تم رصد مكالمة"
    // Receiver that refreshes WebView as soon as a call-detected broadcast arrives
    private val callDetectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // تأخير بسيط ليتسع الوقت للـ Backend يسجل المكالمة قبل التحديث
            // Short delay so the backend has time to record the call before we refresh
            Handler(Looper.getMainLooper()).postDelayed({
                webView.reload()
            }, 1_500L)
        }
    }

    // تجديد دوري كل 60 ثانية لضمان ظهور البيانات حتى لو فات الـ broadcast
    // Periodic 60 s refresh as safety net in case the broadcast was missed
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

        // حفظ الإعدادات الثابتة تلقائياً | Save fixed config automatically
        getSharedPreferences("callverify", Context.MODE_PRIVATE).edit()
            .putString("backend_url", DEFAULT_BACKEND_URL)
            .putString("api_key",     DEFAULT_APP_SECRET)
            .apply()

        // ─── إعداد الـ WebView ─────────────────────────────────────────────────
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled     = true
            domStorageEnabled     = true
            loadWithOverviewMode  = true
            useWideViewPort       = true
            databaseEnabled       = true
        }
        // نفتح الروابط الخارجية في المتصفح ونبقي الـ WebView للوحة التحكم فقط
        // Open external links in browser; keep WebView for admin panel only
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.startsWith(DEFAULT_BACKEND_URL)) {
                    false // بنفتحها داخلياً | open internally
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }
            }
        }
        webView.loadUrl(DEFAULT_BACKEND_URL)

        // ─── تشغيل خدمة المراقبة ───────────────────────────────────────────────
        try {
            val svc = Intent(this, CallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        } catch (e: Exception) { e.printStackTrace() }

        // ─── طلب الصلاحيات ────────────────────────────────────────────────────
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            1001
        )

        // ─── إعفاء البطارية ───────────────────────────────────────────────────
        requestIgnoreBatteryOptimizations()

        // ─── طلب "تطبيق الهاتف الافتراضي" ────────────────────────────────────
        // هذه الخطوة هي المفتاح للحصول على رقم المتصل فورياً — تظهر نافذة نظام
        // رسمية، يجب أن يقبلها المستخدم
        // This step is the key to getting the caller number instantly — an official
        // system dialog appears; the user MUST accept it
        requestDefaultDialerRole()
    }

    override fun onResume() {
        super.onResume()
        // تجديد فور العودة للتطبيق | Refresh when app comes to foreground
        webView.reload()

        // الاستماع لبث "تم رصد مكالمة" | Listen for call-detected broadcasts
        val filter = IntentFilter(ACTION_CALL_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callDetectedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(callDetectedReceiver, filter)
        }

        // بدء التجديد الدوري | Start periodic refresh
        handler.postDelayed(periodicRefresher, 60_000L)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(callDetectedReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(periodicRefresher)
    }

    override fun onBackPressed() {
        // الـ WebView يتصفح للخلف بدل إغلاق التطبيق | WebView goes back instead of closing
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    private fun requestDefaultDialerRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                        REQUEST_DEFAULT_DIALER
                    )
                }
            } else {
                val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (tm.defaultDialerPackage != packageName) {
                    startActivityForResult(
                        Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                        },
                        REQUEST_DEFAULT_DIALER
                    )
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEFAULT_DIALER) {
            // بعد قبول/رفض دور تطبيق الهاتف، نجدد الـ WebView
            // After accepting/declining the phone-app role, refresh WebView
            webView.reload()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    companion object {
        private const val REQUEST_DEFAULT_DIALER = 2002
    }
}
