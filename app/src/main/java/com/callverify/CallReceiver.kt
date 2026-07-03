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
import android.database.Cursor
import android.provider.CallLog
import android.telephony.TelephonyManager
import kotlinx.coroutines.*

// ============================================================
// ملاحظة مهمة | Important note
// ------------------------------------------------------------
// 🇸🇦 منذ أندرويد 10 (API 29)، نظام أندرويد يمنع تطبيقات الطرف
//    الثالث (غير تطبيق الهاتف الافتراضي) من استقبال EXTRA_INCOMING_NUMBER
//    ضمن بث ACTION_PHONE_STATE_CHANGED، حتى لو مُنحت كل الأذونات.
//    لذلك نعتمد بدلاً من ذلك على قراءة سجل المكالمات (CallLog)
//    بعد انتهاء الرنين مباشرة، وهي الطريقة الموثوقة الوحيدة
//    المتاحة لتطبيق خارجي على أندرويد الحديث.
//
// 🇬🇧 Since Android 10 (API 29), the OS blocks third-party
//    (non-default-dialer) apps from receiving EXTRA_INCOMING_NUMBER
//    in the ACTION_PHONE_STATE_CHANGED broadcast, even with all
//    permissions granted. Instead, we read the CallLog content
//    provider right after the call ends/rings out — this is the
//    only reliable method available to a third-party app on
//    modern Android.
// ============================================================
class CallReceiver : BroadcastReceiver() {

    companion object {
        @Volatile
        private var wasRinging = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        // نستقبل فقط أحداث تغيير حالة الهاتف | Only handle phone state changes
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // الهاتف بدأ يرن — نسجل ذلك وننتظر انتهاء الرنين
                // Phone started ringing — remember this and wait for it to stop
                wasRinging = true
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // إذا كان يرن ثم عاد لحالة الخمول = مكالمة فائتة (لم تُجب)
                // If it was ringing and returned to idle = missed call (not answered)
                if (wasRinging) {
                    wasRinging = false
                    val appContext = context.applicationContext
                    CoroutineScope(Dispatchers.IO).launch {
                        // نمهل النظام لحظة ليكتب السجل في CallLog قبل قراءته
                        // Give the system a moment to write the entry to CallLog
                        delay(1500)
                        val callerNumber = readLastMissedCallNumber(appContext)
                        if (callerNumber != null) {
                            sendCallerToBackend(appContext, callerNumber)
                        }
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // تم الرد على المكالمة — لا تُعتبر فائتة | Call was answered — not missed
                wasRinging = false
            }
        }
    }

    private fun readLastMissedCallNumber(context: Context): String? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val type = if (typeIndex >= 0) cursor.getInt(typeIndex) else -1
                val number = if (numberIndex >= 0) cursor.getString(numberIndex) else null
                if (type == CallLog.Calls.MISSED_TYPE && !number.isNullOrBlank()) number else null
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            cursor?.close()
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
