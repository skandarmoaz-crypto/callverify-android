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
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

// رابط الاستضافة الثابت وسر التطبيق — مضمّنان داخلياً، لا واجهة إعدادات للمستخدم
// Fixed hosting URL and app secret — hardcoded internally, no user-facing settings screen
const val DEFAULT_BACKEND_URL = "https://listen-to-me--emoazvjd8.replit.app"
const val DEFAULT_APP_SECRET = "callverify-app-secret-2024"

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // نحفظ الإعدادات الثابتة داخلياً تلقائياً — بدون أي تدخل أو واجهة من المستخدم
        // Auto-save the fixed configuration internally — no user interaction or UI needed
        val prefs = getSharedPreferences("callverify", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("backend_url", DEFAULT_BACKEND_URL)
            .putString("api_key", DEFAULT_APP_SECRET)
            .apply()

        // عرض لوحة التحكم بملء الشاشة | Show the admin panel filling the entire screen
        val webView = findViewById<android.webkit.WebView>(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = android.webkit.WebViewClient()
        webView.loadUrl(DEFAULT_BACKEND_URL)

        // تشغيل خدمة المراقبة في الخلفية أولاً (لا تحتاج صلاحية خاصة)
        // Start the background monitoring service first (no special permission needed)
        try {
            val serviceIntent = Intent(this, CallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            // لا نكسر التطبيق إذا فشل تشغيل الخدمة | Don't crash the app if the service fails to start
            e.printStackTrace()
        }

        // طلب الصلاحيات | Request permissions
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            1001
        )

        // طلب استثناء التطبيق من تحسين البطارية — ضروري لضمان استمرار عمل
        // خدمة المراقبة في الخلفية دون أن يوقفها النظام (Doze/App Standby)
        // Request battery-optimization exemption — required so the background
        // monitoring service isn't killed by the OS (Doze/App Standby)
        requestIgnoreBatteryOptimizations()

        // طلب أن يصبح هذا التطبيق هو "تطبيق الهاتف الافتراضي" — عندها يعطينا
        // نظام أندرويد رقم المتصل مباشرة ولحظياً بدون أي اعتماد على سجل
        // المكالمات، وهذا يظهر للمستخدم كنافذة نظام رسمية تسأل: "هل تريد
        // السماح لتطبيق CallVerify أن يكون تطبيق الهاتف الافتراضي؟"
        // Request to become the "default Phone app" — Android then hands us
        // the caller number directly and instantly, with no CallLog
        // dependency at all. This shows the user an official system dialog:
        // "Do you want CallVerify to be your default Phone app?"
        requestDefaultDialerRole()
    }

    private fun requestDefaultDialerRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
                ) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(intent, REQUEST_DEFAULT_DIALER)
                }
            } else {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (telecomManager.defaultDialerPackage != packageName) {
                    val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                        putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                    }
                    startActivityForResult(intent, REQUEST_DEFAULT_DIALER)
                }
            }
        } catch (e: Exception) {
            // بعض الأجهزة قد لا تدعم هذا الدور | Some devices may not support this role
            e.printStackTrace()
        }
    }

    companion object {
        private const val REQUEST_DEFAULT_DIALER = 2002
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            // بعض الأجهزة (خصوصاً بعض واجهات الشركات المصنّعة) قد تمنع هذا الطلب
            // Some devices (especially certain OEM skins) may block this request
            e.printStackTrace()
        }
    }
}
