// ============================================================
// ⚠️ تحذير | WARNING
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// يعمل فقط عندما يكون التطبيق هو تطبيق الهاتف الافتراضي
// Only active when the app is set as the default Phone app
class CallVerifyInCallService : InCallService() {

    companion object {
        @Volatile var currentCall: Call? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call

        val isIncoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING
        val number     = call.details.handle?.schemeSpecificPart

        if (isIncoming) {
            val prefs      = getSharedPreferences("callverify", Context.MODE_PRIVATE)
            val autoReject = prefs.getBoolean(PREF_AUTO_REJECT, false)

            if (autoReject) {
                // ✅ رفض تلقائي: نرفض المكالمة فوراً ثم نبلّغ البـ backend بالرقم
                // ✅ Auto-reject: reject immediately then report the number to backend
                call.reject(false, null)

                if (!number.isNullOrBlank()) {
                    scope.launch {
                        CallLogReporter.reportNumberDirectly(applicationContext, number)
                    }
                }

                // لا نفتح InCallActivity — المكالمة انرفضت تلقائياً
                // Don't open InCallActivity — call was auto-rejected
                return
            }

            // ─── الوضع الطبيعي: عرض شاشة المكالمة ──────────────────────────────
            if (!number.isNullOrBlank()) {
                scope.launch {
                    CallLogReporter.reportNumberDirectly(applicationContext, number)
                }
            }

            try {
                startActivity(Intent(this, InCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                })
            } catch (e: Exception) { e.printStackTrace() }
        }

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                when (state) {
                    Call.STATE_DISCONNECTED  -> { if (currentCall == c) currentCall = null }
                    Call.STATE_DISCONNECTING -> {
                        // backup: إرسال احتياطي في حال كان الرقم غير متاح في onCallAdded
                        val num = c.details?.handle?.schemeSpecificPart
                        if (!num.isNullOrBlank() &&
                            c.details?.callDirection == Call.Details.DIRECTION_INCOMING) {
                            scope.launch {
                                CallLogReporter.reportNumberDirectly(applicationContext, num)
                            }
                        }
                    }
                }
            }
        })
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (currentCall == call) currentCall = null
    }
}
