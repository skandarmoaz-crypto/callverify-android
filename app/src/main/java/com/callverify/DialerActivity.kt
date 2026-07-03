// ============================================================
// ⚠️ تحذير | WARNING
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

// شاشة اتصال بسيطة لازمة كي يكون التطبيق مؤهلاً لدور "تطبيق الهاتف
// الافتراضي" — تفتح عند اختيار رقم من جهات الاتصال أو عند طلب الاتصال
// Minimal dialer screen required for the app to qualify for the "default
// phone app" role — opens when a number is picked from contacts or a
// call is requested
class DialerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialer)

        val numberInput = findViewById<EditText>(R.id.numberInput)
        intent?.data?.schemeSpecificPart?.let { numberInput.setText(it) }

        findViewById<Button>(R.id.callButton).setOnClickListener {
            val number = numberInput.text.toString().trim()
            if (number.isNotEmpty()) placeCall(number)
        }
    }

    private fun placeCall(number: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 2001)
            return
        }
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.placeCall(Uri.fromParts("tel", number, null), null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
