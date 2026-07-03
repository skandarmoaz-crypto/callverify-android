// ============================================================
// ⚠️ تحذير | WARNING
// ------------------------------------------------------------
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
//    رابط Backend يُدخله المستخدم في إعدادات التطبيق.
//
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
//    Backend URL is entered by the user in app settings.
// ============================================================

package com.callverify

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

// رابط الاستضافة الثابت — لا يحتاج المستخدم لإدخاله يدوياً
// Fixed hosting URL — user does not need to enter it manually
const val DEFAULT_BACKEND_URL = "https://hear-me--moazbad237.replit.app"
const val DEFAULT_APP_SECRET = "callverify-app-secret-2024"

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("callverify", MODE_PRIVATE)

        val urlField = findViewById<EditText>(R.id.backendUrl)
        val keyField = findViewById<EditText>(R.id.apiKey)
        val saveBtn = findViewById<Button>(R.id.saveBtn)
        val statusText = findViewById<TextView>(R.id.status)
        val webView = findViewById<android.webkit.WebView>(R.id.webView)

        // تحميل القيم المحفوظة، أو استخدام الرابط الافتراضي إن لم توجد
        // Load saved values, or fall back to the default backend URL
        val savedUrl = prefs.getString("backend_url", "")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BACKEND_URL
        val savedKey = prefs.getString("api_key", "")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_APP_SECRET

        if (prefs.getString("backend_url", "").isNullOrBlank()) {
            prefs.edit()
                .putString("backend_url", DEFAULT_BACKEND_URL)
                .putString("api_key", DEFAULT_APP_SECRET)
                .apply()
        }

        urlField.setText(savedUrl)
        keyField.setText(savedKey)

        // حفظ الإعدادات | Save settings
        saveBtn.setOnClickListener {
            prefs.edit()
                .putString("backend_url", urlField.text.toString().trim())
                .putString("api_key", keyField.text.toString().trim())
                .apply()
            statusText.text = "✅ تم الحفظ — التطبيق جاهز | Saved — App is ready"
        }

        // عرض لوحة التحكم داخل التطبيق | Show the admin panel inside the app
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = android.webkit.WebViewClient()
        webView.loadUrl(savedUrl)

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

        // تشغيل خدمة المراقبة في الخلفية | Start background monitoring service
        val serviceIntent = Intent(this, CallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
