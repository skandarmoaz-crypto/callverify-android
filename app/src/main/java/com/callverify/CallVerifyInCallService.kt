// ============================================================
// ⚠️ تحذير | WARNING
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ============================================================
// 🇸🇦 هذه الخدمة تعمل فقط عندما يكون التطبيق هو "تطبيق الهاتف الافتراضي".
//    عندها يسلّمنا نظام أندرويد رقم المتصل مباشرة ولحظياً عبر
//    call.details.handle — بدون أي تأخير أو اعتماد على سجل المكالمات.
//
// 🇬🇧 This service only runs once the app is set as the "default Phone app".
//    Android then hands us the caller number directly and instantly via
//    call.details.handle — no delay, no CallLog dependency.
// ============================================================
class CallVerifyInCallService : InCallService() {

    companion object {
        @Volatile var currentCall: Call? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        // ✅ نُعيّن الـ call أولاً قبل فتح InCallActivity لتجنب race condition
        // ✅ Set currentCall BEFORE launching InCallActivity to avoid race condition
        currentCall = call

        val isIncoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING
        val number = call.details.handle?.schemeSpecificPart

        // إرسال فوري للرقم للـ Backend | Immediate number report to backend
        if (isIncoming && !number.isNullOrBlank()) {
            scope.launch {
                CallLogReporter.reportNumberDirectly(applicationContext, number)
            }
        }

        // تسجيل callback لرصد تغييرات الحالة | Register callback to track state changes
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                when (state) {
                    Call.STATE_DISCONNECTED -> {
                        if (currentCall == c) currentCall = null
                    }
                    // ✅ إرسال احتياطي عند انتهاء المكالمة — يغطي الحالات التي
                    //    يتأخر فيها handle للظهور في onCallAdded (بعض الشركات)
                    // ✅ Backup report on disconnect — covers cases where handle
                    //    is delayed in onCallAdded (happens on some OEMs)
                    Call.STATE_DISCONNECTING -> {
                        val num = c.details?.handle?.schemeSpecificPart
                        if (!num.isNullOrBlank() && c.details?.callDirection == Call.Details.DIRECTION_INCOMING) {
                            scope.launch {
                                CallLogReporter.reportNumberDirectly(applicationContext, num)
                            }
                        }
                    }
                }
            }
        })

        // فتح شاشة المكالمة | Open call screen
        try {
            val intent = Intent(this, InCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (currentCall == call) currentCall = null
    }
}
