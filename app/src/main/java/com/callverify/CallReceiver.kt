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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import kotlinx.coroutines.*

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // نستقبل فقط أحداث تغيير حالة الهاتف | Only handle phone state changes
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        // نعمل فقط عندما يرن الهاتف | Only act when ringing
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val callerNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?: return // إذا لم يوجد رقم (مجهول) نتجاهل | Ignore unknown callers

        // نُرسل الرقم للـ Backend بشكل غير متزامن | Send number to Backend asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            sendCallerToBackend(context, callerNumber)
        }
    }

    private suspend fun sendCallerToBackend(context: Context, callerNumber: String) {
        val prefs = context.getSharedPreferences("callverify", Context.MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "") ?: return
        val apiKey = prefs.getString("api_key", "") ?: return

        if (backendUrl.isEmpty() || apiKey.isEmpty()) return

        try {
            val retrofit = ApiService.build(backendUrl)
            retrofit.reportIncomingCall(
                appSecret = apiKey,
                body = IncomingCallBody(callerPhone = callerNumber)
            )
            // نجح الإرسال | Success — can log to LogCat for debugging
        } catch (e: Exception) {
            // فشل الإرسال — نُسجل للتتبع فقط | Failed — log for debugging only
            e.printStackTrace()
        }
    }
}
